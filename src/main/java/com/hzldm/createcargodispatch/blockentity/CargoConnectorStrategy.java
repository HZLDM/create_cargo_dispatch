package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.blockentity.CargoConnectorSerializer.State;
import com.hzldm.createcargodispatch.cargo.CargoAttacher;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoDetacher;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.OrderData;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 货箱连接器策略（红石驱动），采用<b>融合/切割</b>对称模型：
 * <ul>
 *   <li><b>连接（红石 ON）</b>：回连旧箱（{@link #MAX_PULL_DISTANCE} 内）→ 附近独立货箱
 *       → 地面静态货箱，按优先级把最近的货箱<b>融合进载具 plot</b>，与载具成为同一个 SubLevel；</li>
 *   <li><b>断开（红石 OFF）</b>：把货箱从 plot <b>切割</b>为新的独立 SubLevel，就地分离。</li>
 * </ul>
 * 同体结构无约束误差，比 FixedConstraint 可靠；两个方向互为逆操作。
 */
public final class CargoConnectorStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Connector");
    /** 相邻两次操作最小间隔（防抖） */
    private static final long ACTION_COOLDOWN_TICKS = 10L;
    /** 红石 ON 时，可把断开货箱拉回的最大距离（方块，按中心距） */
    public static final double MAX_PULL_DISTANCE = 16.0D;

    private CargoConnectorStrategy() {}

    /** 连接器每 tick 入口（由 BE 的 sable$tick 调用，运行在 Sable 物理链路：异常必须兜底，绝不炸服务器） */
    public static void tick(Context ctx, Object vehicleSubLevel) {
        try {
            State s = ctx.state();
            long now = ctx.overworld().getGameTime();
            if (now - ctx.lastActionTick() < ACTION_COOLDOWN_TICKS) return;

            if (ctx.isPowered()) {
                if (!s.attached) {
                    tryConnect(ctx, vehicleSubLevel);
                }
            } else if (s.attached) {
                release(ctx, vehicleSubLevel);
            }
        } catch (Throwable t) {
            LOGGER.error("[CargoConnector] tick 异常已捕获，本 tick 跳过（不影响服务器）", t);
        }
    }

    // ========================================================================
    // 断开：同体→切割新 SubLevel；旧约束型→移除约束（历史数据兜底）
    // ========================================================================

    private static void release(Context ctx, Object vehicle) {
        ServerLevel sl = ctx.overworld();
        State s = ctx.state();

        // 1) 约束型（有约束句柄）：移除 FixedConstraint，货箱就地释放
        if (s.constraintHandle != null) {
            SubLevelScanner.removeConstraintHandle(s.constraintHandle);
            s.attached = false;
            s.constraintHandle = null;
            // 保留 s.cargoUuid 用于红石 ON 时回连；不传送货箱

            ctx.setLastActionTick(sl.getGameTime());
            ctx.markChangedAndSync();
            Vector3d releaseContact = contactPoint(vehicle, ctx.blockPos());
            if (releaseContact != null) burst(sl, releaseContact, ParticleTypes.CLOUD, 12);
            LOGGER.info("[CargoConnector] 断开：货箱 {} 就地释放（约束已移除）", s.cargoUuid);
            return;
        }

        // 2) 同体附着型（无约束句柄）。
        if (s.vehicleUuid == null) {
            // 无载具信息的损坏态：不是有效连接，清掉（幽灵占用另有 serverTick 对账）
            s.attached = false;
            ctx.markChangedAndSync();
            return;
        }
        // 不再以 isAttachedVehicleCargo 标记为前置（它在重进/重建时会瞬时失真）。
        // 直接尝试切割：物理真相由 CargoDetacher 判断；条件不满足返回 null，
        // 本 tick 不更新防抖 → 下一 tick 继续，形成「断开红石后循环切割直到真正分离」。
        detachAttached(ctx, vehicle, sl, s);
    }

    /**
     * 同体附着型断开：把货箱方块切割为新的独立 SubLevel（临时位置装配→精确原位）。
     * 临时空间不足时保持 attached、不更新防抖时间，下 tick 自动重试。
     */
    private static void detachAttached(Context ctx, Object vehicle, ServerLevel sl, State s) {
        UUID vehicleUuid = s.vehicleUuid;
        List<BlockPos> gridBlocks = CargoManager.getBlocksBySubLevel(vehicleUuid);
        BlockPos gridMain = CargoManager.getStartPosBySubLevel(vehicleUuid);
        CargoData cargoData = CargoManager.getCargoDataBySubLevel(vehicleUuid);
        List<ItemStack> snapshot = CargoManager.getInventorySnapshot(vehicleUuid);

        // 陈旧态：CargoManager 已无该货箱数据（已被收货/其他路径清理），不是有效连接，终止
        if (gridBlocks == null || gridBlocks.isEmpty() || gridMain == null || cargoData == null) {
            s.attached = false;
            s.cargoUuid = null;
            ctx.markChangedAndSync();
            LOGGER.info("[CargoConnector] 断开：无有效货箱数据（已清理），连接器复位");
            return;
        }

        CargoDetacher.Result r = CargoDetacher.detach(
                sl, vehicle, gridBlocks, gridMain, cargoData, snapshot);
        if (r == null) {
            // 无法安全切割：状态保持，下 tick 自动重试（不刷日志）
            LOGGER.debug("[CargoConnector] 暂不满足分离条件，下 tick 重试");
            return;
        }

        // 索引层：附着记录（载具UUID）→ 新独立货箱
        CargoManager.detachAttachedCargo(vehicleUuid);
        if (r.newUuid() != null) {
            SimpleContainer restore = CargoDetacher.containerFromSnapshot(snapshot);
            CargoManager.registerSubLevel(
                    r.newUuid(), r.worldMain(), r.worldPositions(), restore, cargoData);
            s.cargoUuid = r.newUuid();
        } else {
            // 装配失败兜底：方块已作为静态货箱留在世界，检测器静态路径仍可收货
            CargoManager.register(r.worldMain(), cargoData);
            s.cargoUuid = null;
        }

        s.attached = false;
        s.constraintHandle = null;
        updateOrderSubLevelUuid(cargoData, r.newUuid(), sl);

        ctx.setLastActionTick(sl.getGameTime());
        ctx.markChangedAndSync();
        Vector3d contact = contactPoint(vehicle, ctx.blockPos());
        if (contact != null) burst(sl, contact, ParticleTypes.CLOUD, 12);
        LOGGER.info("[CargoConnector] 断开：货箱已切割分离 新UUID={}", r.newUuid());
    }

    /**
     * 同步订单的 SubLevel UUID（切割/融合后物理真源变了），持久化保证收货/放弃定位正确。
     * cargoData 或订单缺失时安全跳过。
     */
    private static void updateOrderSubLevelUuid(CargoData cargoData, UUID targetUuid, ServerLevel sl) {
        if (cargoData == null) return;
        try {
            OrderData order = OrderManager.getOrder(cargoData.getOrderId());
            if (order != null) {
                order.setSubLevelUuid(targetUuid);
                OrderManager.markChanged(sl);
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoConnector] 更新订单 SubLevel UUID 失败 order={}",
                    cargoData.getOrderId(), t);
        }
    }

    // ========================================================================
    // 连接器被玩家破坏：等同于断开——切割货箱为独立 SubLevel
    // ========================================================================

    /**
     * 连接器方块实际破坏前回调（由 CargoConnectorBlock.playerWillDestroy 调用）。
     * 原理：红石 OFF 的切割由连接器每 tick 驱动，破坏后 BE 消失、该路径不再有机会运行；
     *       故在破坏前补执行同一套切割，保证货箱不随连接器死亡而困死在 plot 内。
     * 必须在玩家破坏前调用：此时 BE 与 plot 方块完整、坐标变换有效。
     */
    public static void onConnectorDestroyed(Level level, BlockPos pos) {
        try {
            if (!(level instanceof ServerLevel sl) || level.isClientSide()) return;
            if (!(level.getBlockEntity(pos) instanceof CargoConnectorBlockEntity connector)) return;
            State s = connector.state();
            if (!s.attached) return;
            // 约束型（旧数据兜底）：移除 FixedConstraint，货箱作为独立 SubLevel 自然分离
            if (s.constraintHandle != null) {
                SubLevelScanner.removeConstraintHandle(s.constraintHandle);
                s.attached = false;
                s.constraintHandle = null;
                return;
            }
            // 同体附着型：无载具信息则静默；有 vehicleUuid 则找到载具直接切割（不依赖标记）
            if (s.vehicleUuid == null) {
                return;
            }
            Object vehicle = SubLevelScanner.findSubLevelByUuid(sl, s.vehicleUuid);
            if (vehicle == null) {
                LOGGER.warn("[CargoConnector] 破坏时找不到载具 SubLevel {}，无法切割（附着数据保留待收货/放弃清理）",
                        s.vehicleUuid);
                return;
            }
            // 复用红石 OFF 的完整切割流程（connector 自身即 Context，零重构）
            detachAttached(connector, vehicle, sl, s);
            if (s.attached) {
                // 切割条件未满足（极端拥挤、临时空间不足）：连接器仍被删除，记录日志
                LOGGER.warn("[CargoConnector] 破坏连接器时切割条件未满足，货箱暂留载具，待收货/放弃时清理");
            }
        } catch (Throwable t) {
            // 绝不让破坏清理异常阻断方块删除流程
            LOGGER.error("[CargoConnector] 破坏连接器清理异常（不影响方块删除）", t);
        }
    }

    // ========================================================================
    // 连接：优先回连旧（距离内）→ 否则自动连接附近货箱
    // ========================================================================

    private static void tryConnect(Context ctx, Object vehicle) {
        ServerLevel sl = ctx.overworld();
        State s = ctx.state();
        BlockPos connector = ctx.blockPos();
        Vector3d contact = contactPoint(vehicle, connector);
        if (contact == null) {
            // 载具 SubLevel 坐标暂不可用（刚加载/物理未就绪）：本 tick 跳过，下 tick 自动重试
            LOGGER.debug("[CargoConnector] 载具坐标暂不可用，跳过本次连接");
            return;
        }
        BlockPos contactBp = BlockPos.containing(contact.x, contact.y, contact.z);

        // 1) 回连上次断开的货箱（16 格内 → 融合为同体）
        if (s.cargoUuid != null) {
            Object cargo = SubLevelScanner.findSubLevelByUuid(sl, s.cargoUuid);
            if (cargo != null && centerDistance(cargo, contact) <= MAX_PULL_DISTANCE) {
                CargoAttacher.MergeResult r = CargoAttacher.mergeSubLevel(sl, vehicle, connector, cargo);
                if (r != null) { finishConnect(ctx, sl, s, vehicle, r); return; }
            } else if (cargo != null) {
                LOGGER.info("[CargoConnector] 货箱距离过远（>{}），不回连，改找附近货箱",
                        (int) MAX_PULL_DISTANCE);
            }
        }

        // 2) 附近独立物理货箱：最近的一个（融合）
        Object nearbyCargo = findNearbyCargoSubLevel(sl, contactBp, vehicle);
        if (nearbyCargo != null) {
            CargoAttacher.MergeResult r = CargoAttacher.mergeSubLevel(sl, vehicle, connector, nearbyCargo);
            if (r != null) { finishConnect(ctx, sl, s, vehicle, r); return; }
        }

        // 3) 附近地面静态货箱（融合）
        BlockPos staticMain = CargoManager.findNearestStaticCargoMain(
                contactBp, SubLevelScanner.rangeXz(), SubLevelScanner.rangeY());
        if (staticMain != null) {
            CargoAttacher.MergeResult r = CargoAttacher.mergeStatic(sl, vehicle, connector, staticMain);
            if (r != null) finishConnect(ctx, sl, s, vehicle, r);
        }
    }

    /** 连接成功统一收尾（所有路径一致）：落同体状态、同步订单、反馈粒子 */
    private static void finishConnect(Context ctx, ServerLevel sl, State s,
                                        Object vehicle, CargoAttacher.MergeResult r) {
        UUID vehicleUuid = SubLevelScanner.getSubLevelUuid(vehicle);
        s.attached = true;
        s.vehicleUuid = vehicleUuid;
        // 同体型货箱无独立 UUID，以载具 UUID 为真源
        s.cargoUuid = vehicleUuid;
        s.constraintHandle = null;
        // 订单指向融合后的载具
        updateOrderSubLevelUuid(r.cargoData(), vehicleUuid, sl);

        ctx.setLastActionTick(sl.getGameTime());
        ctx.markChangedAndSync();
        Vector3d contact = contactPoint(vehicle, ctx.blockPos());
        if (contact != null) burst(sl, contact, ParticleTypes.HAPPY_VILLAGER, 10);
        LOGGER.info("[CargoConnector] 已连接：货箱融合为同体 SubLevel {}", vehicleUuid);
    }

    /**
     * 查找附近独立的货箱 SubLevel，返回中心最近的一个。必须同时满足：
     * <ol>
     *   <li>在 CargoManager 登记了 cargoData（是货箱而非普通载具）；</li>
     *   <li><b>不处于附着状态</b>：附着标记表示「货箱已融合进该载具 plot」，
     *       该 SubLevel 是载具本身，绝不能被其他连接器当独立货箱拾取，
     *       否则会触发 removeSubLevel 把整艘载具删掉；</li>
     *   <li>不是连接器所在载具。</li>
     * </ol>
     */
    private static Object findNearbyCargoSubLevel(ServerLevel sl, BlockPos center, Object vehicle) {
        Object best = null;
        double bestDist = Double.MAX_VALUE;
        for (Object cand : SubLevelScanner.getNearbySubLevels(
                sl, center, SubLevelScanner.rangeXz(), SubLevelScanner.rangeY())) {
            if (cand == vehicle) continue;
            UUID cuuid = SubLevelScanner.getSubLevelUuid(cand);
            if (cuuid == null
                    || CargoManager.getCargoDataBySubLevel(cuuid) == null
                    || CargoManager.isAttachedVehicleCargo(cuuid)) continue;
            double[] p = SubLevelScanner.readSubLevelPosition(cand);
            if (Double.isNaN(p[0])) continue;
            double dx = p[0] - (center.getX() + 0.5);
            double dy = p[1] - (center.getY() + 0.5);
            double dz = p[2] - (center.getZ() + 0.5);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) { bestDist = d; best = cand; }
        }
        return best;
    }

    // ========================================================================
    // 几何 / 反馈
    // ========================================================================

    /** 连接器顶面中心（世界坐标）；坐标转换暂不可用时返回 null，调用方须跳过本 tick */
    @org.jetbrains.annotations.Nullable
    private static Vector3d contactPoint(Object vehicle, BlockPos connectorLocal) {
        // innerToWorld 契约为「失败 null」；此处直接透传，不再二次构造（其每次返回新对象，无共享引用风险）
        return com.hzldm.createcargodispatch.cargo.SableCoords.innerToWorld(
                vehicle,
                connectorLocal.getX() + 0.5,
                connectorLocal.getY() + 1.0,
                connectorLocal.getZ() + 0.5);
    }

    /** 货箱中心到连接器接触点的直线距离 */
    private static double centerDistance(Object cargo, Vector3d contact) {
        double[] p = SubLevelScanner.readSubLevelPosition(cargo);
        if (Double.isNaN(p[0])) return Double.MAX_VALUE;
        double dx = p[0] - contact.x, dy = p[1] - contact.y, dz = p[2] - contact.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 在指定位置生成粒子反馈 */
    private static void burst(ServerLevel sl, Vector3d at, net.minecraft.core.particles.ParticleOptions type, int count) {
        try {
            sl.sendParticles(type, at.x, at.y, at.z, count, 0.25, 0.25, 0.25, 0.0D);
        } catch (Throwable ignore) {
        }
    }

    /** 策略上下文（由连接器 BE 实现；DIP，不直接依赖 BE） */
    public interface Context {
        ServerLevel overworld();
        BlockPos blockPos();
        State state();
        long lastActionTick();
        void setLastActionTick(long tick);
        /** 连接器是否被红石通电（POWERED） */
        boolean isPowered();
        void markChangedAndSync();
    }
}
