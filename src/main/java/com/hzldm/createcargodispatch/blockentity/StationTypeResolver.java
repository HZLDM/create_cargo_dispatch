package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationGroupHelper;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 货运站类型/编号解析器
 *
 * 组身份模型（编号规则）：
 *  - 货运站是组锚点：每个新放置的站永远获得维度内唯一随机编号，绝不复用另一个站的编号
 *    （即使两个同类型站在绑定半径内相邻，编号也不同——站与站是相互独立的组）
 *  - 货物生成器 / 货物接收器：编号与绑定半径内「最近的同类型站」一致；
 *    附近没有站时退化为加入最近同类型设备的临时组；都没有则随机
 *    （兼容"先放设备后放站"：站加载时会直接吸收周围同类型设备的已有编号）
 *  - 在 BlockEntity.onLoad 时，如果 stationId 为空才解析；已有编号（NBT 恢复）绝不重分配
 *  - 类型推断：专属子类（5 种工业站）类型写死；GENERIC 多方块结构继承周围专属类型，
 *    GENERIC 单方块保持 GENERIC 但同样分配随机编号
 */
public final class StationTypeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Station");

    /**
     * 同组扫描半径：与 StationGroupHelper.BIND_RADIUS / 订单 isSameStationCompat 完全对齐。
     * 只在 20×8×20 立方内寻找同组组件，超过该距离（邻居站）绝不共享编号，避免错绑。
     */
    private static final int SCAN_RADIUS_XZ = StationGroupHelper.BIND_RADIUS_XZ;
    private static final int SCAN_RADIUS_Y_NEG = StationGroupHelper.BIND_RADIUS_Y;
    private static final int SCAN_RADIUS_Y_POS = StationGroupHelper.BIND_RADIUS_Y;

    private StationTypeResolver() {}

    /**
     * 尝试推断/分配 stationType 和 stationId
     *
     *  - 调用方两类：
     *      (a) GENERIC 通用货运方块（玩家手放）→ 需要「类型推断 + id 分配」
     *      (b) 专属子类货运方块（5 类 15 个 BE，构造时已写入专属类型）→ 只需要「id 分配/复用」
     *  - stationId 非空（NBT 已恢复或上一轮已分配）直接跳过，绝不在区块加载时重分配
     */
    public static Result resolveIfNeeded(BlockEntity be, StationType stationType, String stationId) {
        // 只看 stationId：有值（已持久化或已分配）就不再做任何推断
        if (stationId != null && !stationId.isEmpty()) {
            return null;
        }
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return null; // 只在服务端做推断
        }
        BlockPos origin = be.getBlockPos();

        // 自身是否为货运站（站是组锚点，编号规则与设备不同）
        final boolean selfIsStation = be instanceof CargoStationBlockEntity;

        // 按「站/设备」两类、按类型分桶记录半径内最近的已有编号（数组下标 = StationType.ordinal）
        int n = StationType.values().length;
        double[] stationBestDist = new double[n];
        String[] stationBestId = new String[n];
        double[] deviceBestDist = new double[n];
        String[] deviceBestId = new String[n];
        java.util.Arrays.fill(stationBestDist, Double.MAX_VALUE);
        java.util.Arrays.fill(deviceBestDist, Double.MAX_VALUE);
        StationType sharedStationType = null; // GENERIC 推断周围专属类型用
        int cargoBlockCount = 0;

        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int minX = origin.getX() - SCAN_RADIUS_XZ;
        final int maxX = origin.getX() + SCAN_RADIUS_XZ;
        final int minY = origin.getY() - SCAN_RADIUS_Y_NEG;
        final int maxY = origin.getY() + SCAN_RADIUS_Y_POS;
        final int minZ = origin.getZ() - SCAN_RADIUS_XZ;
        final int maxZ = origin.getZ() + SCAN_RADIUS_XZ;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!StationGroupHelper.isCargoBlock(level.getBlockState(cursor).getBlock())) continue;
                    cargoBlockCount++;
                    BlockEntity other = level.getBlockEntity(cursor);
                    boolean otherIsStation = other instanceof CargoStationBlockEntity;
                    StationType otherType = null;
                    String otherId = null;
                    if (other instanceof CargoStationBlockEntity sbe) {
                        otherType = sbe.getStationType();
                        otherId = sbe.getStationId();
                    } else if (other instanceof CargoGeneratorBlockEntity gbe) {
                        otherType = gbe.getStationType();
                        otherId = gbe.getStationId();
                    } else if (other instanceof CargoDetectorBlockEntity dbe) {
                        otherType = dbe.getStationType();
                        otherId = dbe.getStationId();
                    }
                    if (otherType == null) continue;
                    // 周围第一个专属类型（GENERIC 多方块推断用，站/设备均可）
                    if (otherType != StationType.GENERIC && sharedStationType == null) {
                        sharedStationType = otherType;
                    }
                    if (otherId == null || otherId.isEmpty()) continue;
                    // 编号按「站/设备」分开、按类型分桶，各取最近
                    int idx = otherType.ordinal();
                    double distSq = cursor.distSqr(origin);
                    if (otherIsStation) {
                        if (distSq < stationBestDist[idx]) {
                            stationBestDist[idx] = distSq;
                            stationBestId[idx] = otherId;
                        }
                    } else {
                        if (distSq < deviceBestDist[idx]) {
                            deviceBestDist[idx] = distSq;
                            deviceBestId[idx] = otherId;
                        }
                    }
                }
            }
        }

        // ===== 选择最终 stationType =====
        final StationType resolvedType;
        if (stationType != StationType.GENERIC) {
            // 专属子类：类型是构造器里写死的，永远不改
            resolvedType = stationType;
        } else if (cargoBlockCount <= 1 || sharedStationType == null) {
            // GENERIC 单方块，或周围只有 GENERIC：保持 GENERIC
            resolvedType = StationType.GENERIC;
        } else {
            // GENERIC 紧邻专属组件群 → 推断为该专属类型并加入同组
            resolvedType = sharedStationType;
        }

        // ===== 分配 stationId =====
        int typeIdx = resolvedType.ordinal();
        String stationIdNear = stationBestId[typeIdx]; // 最近同类型站编号
        String deviceIdNear = deviceBestId[typeIdx];   // 最近同类型设备编号
        final String resolvedId;
        final String idSource;
        if (selfIsStation) {
            // 站是锚点：只吸收「设备」已有编号（兼容结构/先放设备），绝不复用另一个站的编号
            if (deviceIdNear != null && !deviceIdNear.isEmpty()) {
                resolvedId = deviceIdNear;
                idSource = "吸收附近设备组";
            } else {
                resolvedId = StationGroupHelper.generateUniqueStationId(level);
                idSource = "随机新生成(站独立)";
            }
        } else {
            // 设备：编号跟「最近的同类型站」一致；附近没有站才加入设备临时组；都没有则随机
            if (stationIdNear != null && !stationIdNear.isEmpty()) {
                resolvedId = stationIdNear;
                idSource = "绑定最近同类型站";
            } else if (deviceIdNear != null && !deviceIdNear.isEmpty()) {
                resolvedId = deviceIdNear;
                idSource = "加入附近设备临时组";
            } else {
                resolvedId = StationGroupHelper.generateUniqueStationId(level);
                idSource = "随机新生成";
            }
        }

        LOGGER.info("[CargoDispatch] 解析货运组件 {} 站={} → type={} id={} (原type={}, 周围货运方块={}, id来源={})",
                origin, selfIsStation, resolvedType, resolvedId, stationType.getId(), cargoBlockCount, idSource);
        return new Result(resolvedType, resolvedId);
    }

    public record Result(StationType stationType, String stationId) {}
}
