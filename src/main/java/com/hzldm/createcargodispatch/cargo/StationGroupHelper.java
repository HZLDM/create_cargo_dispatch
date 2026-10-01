package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 货运站「同组组件」识别工具（站 ↔ 生成器 ↔ 检测器）
 *
 * 原理：
 *  - 同一货运站的三种组件共享同一个 stationId（放置时由 StationTypeResolver 近距传播）
 *  - 绑定条件（三者同时满足）：同维度、类型兼容、编号相等（旧数据空编号走距离兜底）、在绑定半径内
 *  - 绑定半径 XZ=20/Y=8，与订单「同站判定」isSameStationCompat 完全一致：
 *    太远的生成器/检测器绝不识别为本站组件（避免 48 格扫描错绑邻居站）
 *  - 只读取已加载 chunk 中的 BlockEntity，绝不强制加载区块
 */
public final class StationGroupHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Binding");

    /** 同组组件绑定半径（水平） */
    public static final int BIND_RADIUS_XZ = 20;
    /** 同组组件绑定半径（垂直） */
    public static final int BIND_RADIUS_Y = 8;

    /** 随机编号去重最大尝试次数（8 位十六进制 = 32 位空间，正常不会触达上限） */
    private static final int UNIQUE_ID_MAX_TRIES = 16;

    private StationGroupHelper() {}

    /** 判断方块是否为货运站/生成器/检测器（按 Block 类 O(1) 判定，无需读取 BE） */
    public static boolean isCargoBlock(@Nullable net.minecraft.world.level.block.Block block) {
        return block instanceof com.hzldm.createcargodispatch.block.CargoStationBlock
                || block instanceof com.hzldm.createcargodispatch.block.CargoGeneratorBlock
                || block instanceof com.hzldm.createcargodispatch.block.CargoDetectorBlock;
    }

    /**
     * 货运站放置冲突预检（方块进入世界前调用）
     *
     * 冲突规则（与 StationTypeResolver 的编号解析严格对齐）：
     *  1. 绑定半径内已有「同类型」货运站 → 冲突（距离过近，两个同类型锚点会让设备错绑）；
     *     GENERIC 新站紧邻专属组件群时会被解析器推断为该专属类型，故按推断后类型比较
     *  2. 新站即将吸收的最近同类型设备编号，已被半径外的其他同类型站占用 → 冲突
     *     （两站相距略超 20 格、设备群在中间重叠区时，新站仍会与老站同号）
     *
     * @return 冲突的已有站位置；无冲突返回 null
     */
    @Nullable
    public static BlockPos findStationPlacementConflict(Level level, BlockPos origin, StationType incoming) {
        if (level == null || origin == null || incoming == null) return null;

        int typeCount = StationType.values().length;
        double[] stationDist = new double[typeCount];
        BlockPos[] stationPos = new BlockPos[typeCount];
        double[] deviceDist = new double[typeCount];
        String[] deviceId = new String[typeCount];
        Arrays.fill(stationDist, Double.MAX_VALUE);
        Arrays.fill(deviceDist, Double.MAX_VALUE);
        StationType firstSpecial = null; // GENERIC 新站的类型推断依据

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - BIND_RADIUS_XZ; x <= origin.getX() + BIND_RADIUS_XZ; x++) {
            for (int y = origin.getY() - BIND_RADIUS_Y; y <= origin.getY() + BIND_RADIUS_Y; y++) {
                for (int z = origin.getZ() - BIND_RADIUS_XZ; z <= origin.getZ() + BIND_RADIUS_XZ; z++) {
                    cursor.set(x, y, z);
                    if (!isCargoBlock(level.getBlockState(cursor).getBlock())) continue;
                    BlockEntity other = level.getBlockEntity(cursor);
                    StationType otherType = getComponentType(other);
                    if (otherType == null) continue;
                    if (otherType != StationType.GENERIC && firstSpecial == null) {
                        firstSpecial = otherType;
                    }
                    int idx = otherType.ordinal();
                    double distSq = cursor.distSqr(origin);
                    if (other instanceof CargoStationBlockEntity) {
                        if (distSq < stationDist[idx]) {
                            stationDist[idx] = distSq;
                            stationPos[idx] = cursor.immutable();
                        }
                    } else {
                        String id = getComponentId(other);
                        if (id != null && !id.isEmpty() && distSq < deviceDist[idx]) {
                            deviceDist[idx] = distSq;
                            deviceId[idx] = id;
                        }
                    }
                }
            }
        }

        // GENERIC 站紧邻专属组件群 → 与解析器一致推断为该专属类型，否则保持 GENERIC
        StationType resolvedType = (incoming != StationType.GENERIC)
                ? incoming
                : (firstSpecial != null ? firstSpecial : StationType.GENERIC);
        int idx = resolvedType.ordinal();

        // 规则 1：半径内已有同类型站
        if (stationPos[idx] != null) {
            return stationPos[idx];
        }
        // 规则 2：将吸收的设备编号已被半径外的同类型站占用
        String absorbId = deviceId[idx];
        if (absorbId != null && !absorbId.isEmpty()) {
            for (BlockPos sp : CargoGeneratorRegistry.getStationsByType(level, resolvedType)) {
                if (!level.isLoaded(sp)) continue;
                if (level.getBlockEntity(sp) instanceof CargoStationBlockEntity sbe
                        && absorbId.equals(sbe.getStationId())) {
                    return sp.immutable();
                }
            }
        }
        return null;
    }

    /** 两位置是否在绑定半径内 */
    public static boolean isWithinBindRadius(@Nullable BlockPos a, @Nullable BlockPos b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return Math.abs(a.getX() - b.getX()) <= BIND_RADIUS_XZ
                && Math.abs(a.getY() - b.getY()) <= BIND_RADIUS_Y
                && Math.abs(a.getZ() - b.getZ()) <= BIND_RADIUS_XZ;
    }

    /**
     * 组件匹配：类型严格相等 + 编号兼容
     *  - 类型：必须完全相同（GENERIC 只与 GENERIC 成组），不同工业类型绝不绑定
     *  - 编号：双方都有值时必须相等；任一方为空（旧存档）走距离兜底
     */
    private static boolean componentMatches(@Nullable StationType expectedType, @Nullable String expectedId,
                                            @Nullable StationType actualType, @Nullable String actualId) {
        if (expectedType == null || actualType == null || expectedType != actualType) {
            return false;
        }
        if (expectedId != null && !expectedId.isEmpty()
                && actualId != null && !actualId.isEmpty()
                && !expectedId.equals(actualId)) {
            return false;
        }
        return true;
    }

    /** 读取货运方块 BE 的类型（非货运方块返回 null） */
    @Nullable
    public static StationType getComponentType(@Nullable BlockEntity be) {
        if (be instanceof CargoStationBlockEntity s) return s.getStationType();
        if (be instanceof CargoGeneratorBlockEntity g) return g.getStationType();
        if (be instanceof CargoDetectorBlockEntity d) return d.getStationType();
        return null;
    }

    /** 读取货运方块 BE 的编号（非货运方块返回 null） */
    @Nullable
    public static String getComponentId(@Nullable BlockEntity be) {
        if (be instanceof CargoStationBlockEntity s) return s.getStationId();
        if (be instanceof CargoGeneratorBlockEntity g) return g.getStationId();
        if (be instanceof CargoDetectorBlockEntity d) return d.getStationId();
        return null;
    }

    /**
     * 查找本站绑定的生成器（编号/类型匹配 + 绑定半径内最近的一个）
     * @return 生成器位置；无则 null
     */
    @Nullable
    public static BlockPos findBoundGeneratorPos(Level level, BlockPos center,
                                                 StationType type, String groupId) {
        if (level == null || center == null) return null;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos p : CargoGeneratorRegistry.getGenerators(level)) {
            if (!level.isLoaded(p) || !isWithinBindRadius(center, p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (!(be instanceof CargoGeneratorBlockEntity)) continue;
            if (!componentMatches(type, groupId, getComponentType(be), getComponentId(be))) continue;
            double distSq = p.distSqr(center);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p.immutable();
            }
        }
        return best;
    }

    /**
     * 查找本站绑定的全部检测器（编号/类型匹配 + 绑定半径内）
     * 原理：提交列表只聚合这些检测器，48 格外/不同编号的邻居站检测器绝不混入
     */
    public static List<BlockPos> findBoundDetectorPositions(Level level, BlockPos center,
                                                             StationType type, String groupId) {
        List<BlockPos> result = new ArrayList<>();
        if (level == null || center == null) return result;
        for (BlockPos p : CargoGeneratorRegistry.getDetectorsByType(level,
                type != null ? type : StationType.GENERIC)) {
            if (!level.isLoaded(p) || !isWithinBindRadius(center, p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (!(be instanceof CargoDetectorBlockEntity)) continue;
            if (!componentMatches(type, groupId, getComponentType(be), getComponentId(be))) continue;
            result.add(p.immutable());
        }
        return result;
    }

    /**
     * 从组内任意组件坐标（站/检测器/生成器）解析同组的<b>货运站 BE</b>。
     * 用于生成订单时读取该站配置的货箱尺寸。
     *
     * @param center 组件坐标（订单源坐标）
     * @param type   期望站点类型（null=不限制）
     * @return 绑定半径内最近的同类型同编号站；找不到 null
     */
    @Nullable
    public static CargoStationBlockEntity findBoundStation(Level level, BlockPos center, @Nullable StationType type) {
        if (level == null || center == null) return null;
        String groupId = resolveGroupIdAt(level, center);

        CargoStationBlockEntity exactType = null, genericType = null;
        double exactDistSq = Double.MAX_VALUE, genericDistSq = Double.MAX_VALUE;

        for (StationType t : StationType.values()) {
            for (BlockPos p : CargoGeneratorRegistry.getStationsByType(level, t)) {
                if (!level.isLoaded(p) || !isWithinBindRadius(center, p)) continue;
                BlockEntity be = level.getBlockEntity(p);
                if (!(be instanceof CargoStationBlockEntity station)) continue;

                StationType stType = getComponentType(be);
                String stId = getComponentId(be);
                boolean idOk = !(groupId != null && !groupId.isEmpty()
                        && stId != null && !stId.isEmpty() && !groupId.equals(stId));
                boolean exact = type != null && stType == type;
                boolean generic = stType == StationType.GENERIC;

                if (!idOk) continue;
                if (!exact && !generic) continue;

                double distSq = p.distSqr(center);
                if (exact) {
                    if (distSq < exactDistSq) { exactDistSq = distSq; exactType = station; }
                } else if (type != null) {
                    if (distSq < genericDistSq) { genericDistSq = distSq; genericType = station; }
                }
            }
        }
        return exactType != null ? exactType : genericType;
    }

    /** 判定指定位置的检测器是否属于该货运站（提交时服务端硬校验） */
    public static boolean isDetectorBound(Level level, CargoStationBlockEntity station, BlockPos detectorPos) {
        if (level == null || station == null || detectorPos == null) return false;
        if (!level.isLoaded(detectorPos)) return false;
        BlockEntity be = level.getBlockEntity(detectorPos);
        if (!(be instanceof CargoDetectorBlockEntity detector)) return false;
        return isWithinBindRadius(station.getBlockPos(), detectorPos)
                && componentMatches(station.getStationType(), station.getStationId(),
                        detector.getStationType(), detector.getStationId());
    }

    /**
     * 解析某位置所属同组的编号
     *  - 位置本身是货运方块且有编号：直接返回
     *  - 否则在绑定半径内找最近的、已有编号的货运方块（订单坐标可能是站本体/检测器/生成器任一）
     */
    @Nullable
    public static String resolveGroupIdAt(Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return null;
        BlockEntity direct = level.getBlockEntity(pos);
        String directId = getComponentId(direct);
        if (directId != null && !directId.isEmpty()) return directId;

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        // 站 + 检测器（按 GENERIC 取全类型）+ 生成器 三张表合并候选
        List<BlockPos> candidates = new ArrayList<>();
        for (StationType t : StationType.values()) {
            candidates.addAll(CargoGeneratorRegistry.getStationsByType(level, t));
        }
        candidates.addAll(CargoGeneratorRegistry.getDetectorsByType(level, StationType.GENERIC));
        candidates.addAll(CargoGeneratorRegistry.getGenerators(level));
        for (BlockPos p : candidates) {
            if (!level.isLoaded(p) || !isWithinBindRadius(pos, p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            String id = getComponentId(be);
            if (id == null || id.isEmpty()) continue;
            double distSq = p.distSqr(pos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best != null ? getComponentId(level.getBlockEntity(best)) : null;
    }

    /**
     * 生成维度内唯一的 8 位十六进制随机编号
     * 原理：收集三张注册表中所有已加载组件的现有编号做去重，极小概率冲突则重试
     */
    public static String generateUniqueStationId(Level level) {
        Set<String> used = collectLoadedGroupIds(level);
        for (int i = 0; i < UNIQUE_ID_MAX_TRIES; i++) {
            String id = String.format("%08x", ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL);
            if (used.add(id)) return id;
        }
        // 理论不可达：兜底用时间戳拼接随机数，保证不与现有编号重复
        String fallback = Long.toHexString(System.nanoTime() ^ ThreadLocalRandom.current().nextInt());
        LOGGER.warn("[CargoDispatch] 随机编号连续 {} 次冲突，启用兜底编号 {}", UNIQUE_ID_MAX_TRIES, fallback);
        return fallback.substring(0, Math.min(8, fallback.length()));
    }

    /** 收集维度内所有已加载货运组件的编号（仅已加载区块，不触发区块加载） */
    private static Set<String> collectLoadedGroupIds(Level level) {
        Set<String> ids = new HashSet<>();
        for (StationType t : StationType.values()) {
            for (BlockPos p : CargoGeneratorRegistry.getStationsByType(level, t)) {
                if (level.isLoaded(p)) {
                    String id = getComponentId(level.getBlockEntity(p));
                    if (id != null && !id.isEmpty()) ids.add(id);
                }
            }
        }
        for (BlockPos p : CargoGeneratorRegistry.getDetectorsByType(level, StationType.GENERIC)) {
            if (level.isLoaded(p)) {
                String id = getComponentId(level.getBlockEntity(p));
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        for (BlockPos p : CargoGeneratorRegistry.getGenerators(level)) {
            if (level.isLoaded(p)) {
                String id = getComponentId(level.getBlockEntity(p));
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }
}
