package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoConnectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 附着货箱切割器：把载具 plot 内的货箱切割为<b>新的独立 SubLevel</b>，
 * 并以<b>断开前的精确世界位姿（任意朝向）</b>就位。
 *
 * <h3>精确切割原理</h3>
 * <ol>
 *   <li>记录主方块精确世界中心 exactMain 与载具精确朝向 Qv（logicalPose，任意角度）；</li>
 *   <li>临时网格位置按内部<b>原始朝向直排</b>方块（不旋转结构、不改 HORIZONTAL_AXIS）；</li>
 *   <li>装配新独立 SubLevel，收集 AssemblyTransform 后 inner Level 中的真实方块位置；</li>
 *   <li>主方块相对刚体旋转原点的局部向量 v = Q0⁻¹(center0 − P0)；</li>
 *   <li>目标朝向 Qn = Qv（读取失败则=装配朝向 identity）；目标位置 Pf = exactMain − Qn×v，主方块即精确归位，
 *       其余方块由同一刚体保证相对关系，整箱与原位逐方块重合；</li>
 *   <li>先删 plot 方块（空出自碰撞）→ teleport 精确位姿。</li>
 * </ol>
 *
 * <h3>为什么渲染能跟转</h3>
 * Sable 调度 SubLevel 内 BlockEntity 时，poseStack 会先 mulPose
 * {@code SubLevelRenderData.getTransformation}——该矩阵含 renderPose 的
 * position + orientation（任意四元数）+ scale。BER 按局部坐标绘制的模型
 * 自动变换到世界精确朝向，无需在建造时旋转方块。
 *
 * <h3>失败安全</h3>
 * 临时空间不足/装配失败时清理临时方块、plot 方块一个不删，返回 null，调用方下 tick 重试。
 * 载具朝向读取失败不阻断断开：降级为「位置精确 + 装配朝向（identity）」的已验证路径。
 * teleport 极端失败不回滚、不丢货，持久化坐标按实际位姿记录。
 */
public final class CargoDetacher {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Detacher");

    /**
     * 切割结果。
     * @param newUuid 新物理货箱 UUID；null=装配失败，方块已作为静态货箱留在世界
     */
    public record Result(UUID newUuid, BlockPos worldMain, List<BlockPos> worldPositions) {}

    private CargoDetacher() {}

    /**
     * 执行切割。
     *
     * @param vehicle    载具 SubLevel（精确位姿真源）
     * @param gridBlocks 货箱在 plot 内的全部方块坐标
     * @param gridMain   货箱主方块（plot 内坐标）
     * @param cargoData  货运数据
     * @param snapshot   放置时缓存的物品快照
     * @return 成功结果；无法安全切割返回 null（调用方保持状态、下 tick 重试）
     */
    public static Result detach(ServerLevel sl, Object vehicle,
                                  List<BlockPos> gridBlocks, BlockPos gridMain,
                                  CargoData cargoData, List<ItemStack> snapshot) {
        if (sl == null || vehicle == null || cargoData == null
                || gridBlocks == null || gridBlocks.isEmpty()
                || gridMain == null || !gridBlocks.contains(gridMain)) {
            return null;
        }

        // 1. 主方块精确世界中心（硬前置；载具朝向延后读取，失败不阻断断开）
        Vector3d exactMain = SubLevelScanner.worldPoint(
                vehicle, gridMain.getX() + 0.5, gridMain.getY() + 0.5, gridMain.getZ() + 0.5);
        Object orientObj = SubLevelScanner.readSubLevelOrientation(vehicle);
        if (exactMain == null) {
            LOGGER.debug("[CargoDetach] 主方块精确位置读取失败，下 tick 重试");
            return null;
        }
        Quaterniondc qVehicle = orientObj instanceof Quaterniondc ? (Quaterniondc) orientObj : null;
        if (qVehicle == null) {
            // 朝向不可用不致命：降级为「位置精确 + 装配朝向（identity）」的已验证路径
            LOGGER.debug("[CargoDetach] 载具朝向不可用 orientObj={}，降级为装配朝向断开",
                    orientObj != null ? orientObj.getClass().getName() : "null");
        }

        // 2. 临时世界直排：保持内部原始相对关系（不旋转）
        List<int[]> rels = new ArrayList<>(gridBlocks.size());
        for (BlockPos gp : gridBlocks) {
            rels.add(new int[]{
                    gp.getX() - gridMain.getX(),
                    gp.getY() - gridMain.getY(),
                    gp.getZ() - gridMain.getZ()});
        }
        BlockPos anchor = findAirAnchor(
                sl, BlockPos.containing(exactMain.x, exactMain.y, exactMain.z), rels);
        if (anchor == null) {
            LOGGER.info("[CargoDetach] 附近找不到网格对齐的临时空间，下 tick 自动重试");
            return null;
        }
        List<BlockPos> tempPositions = new ArrayList<>(gridBlocks.size());
        for (int[] r : rels) tempPositions.add(anchor.offset(r[0], r[1], r[2]));

        Level inner = SubLevelScanner.getSubLevelInternalLevel(vehicle);
        if (inner == null) return null;

        // 3. 方块原样复制（HORIZONTAL_AXIS 保持内部朝向；世界朝向由 pose 负责）
        for (int i = 0; i < gridBlocks.size(); i++) {
            sl.setBlock(tempPositions.get(i), inner.getBlockState(gridBlocks.get(i)), Block.UPDATE_ALL);
        }
        restoreBlockEntities(sl, tempPositions, anchor, cargoData, snapshot);

        // 4. 装配新独立物理 SubLevel
        String name = "ConnectorDetach#" + Integer.toHexString(System.identityHashCode(vehicle));
        Object newSub = CargoPhysicsHelper.assembleBlocks(sl, anchor, tempPositions, name);
        if (newSub == null) {
            for (BlockPos p : tempPositions) sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            LOGGER.warn("[CargoDetach] 新 SubLevel 装配失败，已回滚临时方块");
            return null;
        }

        // 5. 收集 inner Level 中 AssemblyTransform 后的真实方块位置与主方块
        InnerCargo innerCargo = collectInnerCargo(newSub);
        if (innerCargo == null) {
            rollback(sl, newSub, tempPositions);
            return null;
        }

        // 6. 初始 pose 与主方块当前世界中心
        double[] p0 = SubLevelScanner.readSubLevelPosition(newSub);
        Object q0Obj = SubLevelScanner.readSubLevelOrientation(newSub);
        BlockPos mainInner = innerCargo.main();
        Vector3d center0 = SubLevelScanner.worldPoint(
                newSub, mainInner.getX() + 0.5, mainInner.getY() + 0.5, mainInner.getZ() + 0.5);
        if (Double.isNaN(p0[0]) || !(q0Obj instanceof Quaterniondc q0) || center0 == null) {
            rollback(sl, newSub, tempPositions);
            return null;
        }

        // 7. 主方块相对刚体旋转原点的局部向量 v = Q0⁻¹(center0 − P0)
        Vector3d vLocal = new Vector3d(
                center0.x - p0[0], center0.y - p0[1], center0.z - p0[2]);
        new Quaterniond(q0).conjugate().transform(vLocal);

        // 8. 目标 pose：朝向优先载具朝向（精确）；不可用时取新 SubLevel 自身朝向（identity 降级）。
        //    Pf = exactMain − Qn×v
        Quaterniond finalOrient = new Quaterniond(qVehicle != null ? qVehicle : q0);
        boolean preciseOrient = qVehicle != null;
        Vector3d rotated = new Vector3d(vLocal);
        finalOrient.transform(rotated);
        Vector3d finalPos = new Vector3d(
                exactMain.x - rotated.x, exactMain.y - rotated.y, exactMain.z - rotated.z);

        // 9. 先删 plot 货箱方块，空出精确原位（避免与尚未移除的 plot 碰撞体重叠爆裂）
        for (BlockPos gp : gridBlocks) {
            inner.setBlock(gp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        // 10. teleport 精确位姿。极端失败不回滚、不丢货
        boolean teleported = SubLevelScanner.teleportToPose(
                sl, newSub, finalPos.x, finalPos.y, finalPos.z, finalOrient);
        if (!teleported) {
            LOGGER.warn("[CargoDetach] teleport 精确位置失败，货箱保留在临时位置 {}", anchor);
        }

        // 11. 持久化世界坐标：按 teleport 后的实际 pose 变换 inner 坐标（失败则=临时位姿，均与物理一致）
        List<BlockPos> worldPositions = new ArrayList<>(innerCargo.blocks().size());
        for (BlockPos ip : innerCargo.blocks()) {
            Vector3d wp = SubLevelScanner.worldPoint(
                    newSub, ip.getX() + 0.5, ip.getY() + 0.5, ip.getZ() + 0.5);
            if (wp == null) {
                // 极端兜底：主方块位姿 + 相对偏移（朝向不确定时回退到临时/最终基准）
                wp = teleported ? finalPos : new Vector3d(center0);
            }
            worldPositions.add(BlockPos.containing(
                    Math.floor(wp.x), Math.floor(wp.y), Math.floor(wp.z)));
        }
        Vector3d mainWorldVec = teleported ? exactMain : center0;
        BlockPos worldMain = BlockPos.containing(
                Math.floor(mainWorldVec.x), Math.floor(mainWorldVec.y), Math.floor(mainWorldVec.z));

        UUID newUuid = SubLevelScanner.getSubLevelUuid(newSub);
        LOGGER.info("[CargoDetach] 货箱已切割为独立 SubLevel {}（主方块 {}，方块 {} 个，朝向精确={}，teleport={}）",
                newUuid, worldMain, worldPositions.size(), preciseOrient, teleported);
        return new Result(newUuid, worldMain, worldPositions);
    }

    /** inner Level 中的货箱方块集合（AssemblyTransform 后）+ 主方块；无法读取返回 null */
    private static InnerCargo collectInnerCargo(Object newSub) {
        int[] box = SubLevelScanner.readPlotBBoxComponents(newSub);
        Level newInner = SubLevelScanner.getSubLevelInternalLevel(newSub);
        if (box == null || newInner == null) return null;

        List<BlockPos> blocks = new ArrayList<>();
        BlockPos main = null;
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    BlockPos ip = BlockPos.containing(x, y, z);
                    if (newInner.getBlockEntity(ip) instanceof CargoBlockEntity cbe) {
                        blocks.add(ip);
                        if (cbe.isMainBlock()) main = ip;
                    }
                }
            }
        }
        if (main == null) {
            if (blocks.isEmpty()) return null;
            main = blocks.get(0); // 理论不会发生（主方块标记装配前已写入）
        }
        return new InnerCargo(blocks, main);
    }

    /** inner 货箱快照：全部方块 + 主方块 */
    private record InnerCargo(List<BlockPos> blocks, BlockPos main) {}

    /** 装配后路径失败：删除新 SubLevel 并清空临时方块，plot 保持完整 */
    private static void rollback(ServerLevel sl, Object newSub, List<BlockPos> tempPositions) {
        SubLevelScanner.removeSubLevel(sl, newSub);
        for (BlockPos p : tempPositions) sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        LOGGER.warn("[CargoDetach] 新 SubLevel 位姿读取失败，已回滚");
    }

    /** 恢复临时方块 BE：主方块 inventory+cargoData，其余 cargoData（assembleBlocks 内还有兜底） */
    private static void restoreBlockEntities(ServerLevel sl, List<BlockPos> tempPositions,
                                               BlockPos main, CargoData cargoData,
                                               List<ItemStack> snapshot) {
        for (BlockPos p : tempPositions) {
            if (!(sl.getBlockEntity(p) instanceof CargoBlockEntity cbe)) continue;
            cbe.setCargoData(cargoData);
            cbe.setMainBlock(p.equals(main));
            if (p.equals(main) && snapshot != null) {
                SimpleContainer inv = cbe.getInventory();
                inv.clearContent();
                InvWrapper w = new InvWrapper(inv);
                for (ItemStack s : snapshot) {
                    ItemHandlerHelper.insertItemStacked(w, s.copy(), false);
                }
            }
            cbe.setChanged();
        }
    }

    /** 从物品快照重建 27 格容器（堆叠压缩放置，供注册快照使用） */
    public static SimpleContainer containerFromSnapshot(List<ItemStack> snapshot) {
        SimpleContainer c = new SimpleContainer(CargoBalance.INVENTORY_SLOTS);
        if (snapshot != null) {
            InvWrapper w = new InvWrapper(c);
            for (ItemStack s : snapshot) {
                ItemHandlerHelper.insertItemStacked(w, s.copy(), false);
            }
        }
        return c;
    }

    /**
     * 查找全部 rel 偏移位置均为空气且已加载的锚点。
     * 候选顺序：原位 → 向上（载具上方通常空旷）→ 向下 → 水平少量偏移。
     */
    private static BlockPos findAirAnchor(ServerLevel sl, BlockPos preferred, List<int[]> rels) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(preferred);
        for (int i = 1; i <= 8; i++) candidates.add(preferred.above(i));
        for (int i = 1; i <= 3; i++) candidates.add(preferred.below(i));
        for (int d = 1; d <= 2; d++) {
            candidates.add(preferred.offset(d, 0, 0));
            candidates.add(preferred.offset(-d, 0, 0));
            candidates.add(preferred.offset(0, 0, d));
            candidates.add(preferred.offset(0, 0, -d));
        }
        for (BlockPos c : candidates) {
            if (allAir(sl, c, rels)) return c;
        }
        return null;
    }

    private static boolean allAir(ServerLevel sl, BlockPos anchor, List<int[]> rels) {
        for (int[] r : rels) {
            BlockPos p = anchor.offset(r[0], r[1], r[2]);
            if (!sl.isLoaded(p) || !sl.getBlockState(p).isAir()) return false;
        }
        return true;
    }

    /**
     * 删除型清理（收货/放弃）后，把连接器 BE 重置为空闲状态。
     * 原理：连接器在主方块正下方一格；若不重置，其 state.attached 残留会永久占用连接器。
     */
    public static void resetConnectorState(ServerLevel sl, UUID vehicleUuid, BlockPos basePos) {
        if (sl == null || vehicleUuid == null || basePos == null) return;
        try {
            Object vehicle = SubLevelScanner.findSubLevelByUuid(sl, vehicleUuid);
            Level inner = vehicle != null ? SubLevelScanner.getSubLevelInternalLevel(vehicle) : null;
            var be = inner != null ? inner.getBlockEntity(basePos.below(1)) : null;
            if (be instanceof CargoConnectorBlockEntity connector) {
                connector.state().clear();
                connector.markChangedAndSync();
                LOGGER.info("[CargoDetach] 连接器已重置为空闲（货箱删除路径）");
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDetach] 重置连接器状态失败：{}", t.getMessage());
        }
    }
}
