package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 货运站点位置注册表
 *
 * 原理：
 *  - 维护两套表：生成器位置表（接单时查找最近生成器）+ 货运站位置表（按类型分类，用于订单目标位置查询）
 *  - CargoGeneratorBlockEntity 加载时注册到 GENERATOR_REGISTRY
 *  - CargoStationBlockEntity 加载时注册到 STATION_REGISTRY（按 StationType 分类）
 *  - 使用 ConcurrentHashMap + CopyOnWriteArrayList 保证线程安全
 *  - 按维度分组，避免跨维度查找
 */
public final class CargoGeneratorRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Registry");

    /** 生成器位置表：按维度分组（用于接单时查找最近生成器） */
    private static final Map<ResourceLocation, CopyOnWriteArrayList<BlockPos>> GENERATOR_REGISTRY = new ConcurrentHashMap<>();

    /** 货运站位置表：按维度+类型分组（用于订单目标位置查询） */
    private static final Map<ResourceLocation, Map<StationType, CopyOnWriteArrayList<BlockPos>>> STATION_REGISTRY = new ConcurrentHashMap<>();

    /** 货物检测器位置表：按维度+类型分组（站自动提交开关变化时就近失效 detector 缓存 + UI 扫描列表示例） */
    private static final Map<ResourceLocation, Map<StationType, CopyOnWriteArrayList<BlockPos>>> DETECTOR_REGISTRY = new ConcurrentHashMap<>();

    private CargoGeneratorRegistry() {
    }

    // ===== 生成器注册（CargoGeneratorBlockEntity 使用） =====

    /** 注册生成器（BlockEntity 加载时调用） */
    public static void registerGenerator(Level level, BlockPos pos) {
        if (level == null || pos == null) return;
        GENERATOR_REGISTRY.computeIfAbsent(level.dimension().location(), k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(pos);
        LOGGER.debug("[CargoDispatch] 注册生成器 @ {} dim={}", pos, level.dimension().location());
    }

    /** 注销生成器（BlockEntity 移除时调用） */
    public static void unregisterGenerator(Level level, BlockPos pos) {
        if (level == null || pos == null) return;
        CopyOnWriteArrayList<BlockPos> list = GENERATOR_REGISTRY.get(level.dimension().location());
        if (list != null) {
            list.remove(pos);
        }
    }

    /**
     * 查找指定维度中距离 pos 最近的生成器位置
     *
     * @return 最近的生成器 BlockPos，无可用生成器时返回 null
     */
    public static BlockPos findNearestGenerator(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        CopyOnWriteArrayList<BlockPos> list = GENERATOR_REGISTRY.get(level.dimension().location());
        if (list == null || list.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos genPos : list) {
            double distSq = genPos.distSqr(pos);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = genPos;
            }
        }
        return nearest;
    }

    /** 获取指定维度的全部生成器位置（返回不可变视图，调用方只读遍历） */
    public static List<BlockPos> getGenerators(Level level) {
        if (level == null) return List.of();
        CopyOnWriteArrayList<BlockPos> list = GENERATOR_REGISTRY.get(level.dimension().location());
        return list != null && !list.isEmpty() ? java.util.Collections.unmodifiableList(list) : List.of();
    }

    // ===== 货运站注册（CargoStationBlockEntity 使用） =====

    /** 注册货运站（BlockEntity 加载时调用） */
    public static void registerStation(Level level, BlockPos pos, StationType stationType) {
        if (level == null || pos == null || stationType == null) return;
        STATION_REGISTRY
                .computeIfAbsent(level.dimension().location(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(stationType, k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(pos);
        LOGGER.debug("[CargoDispatch] 注册货运站 @ {} dim={} type={}", pos, level.dimension().location(), stationType.getId());
    }

    /** 注销货运站（BlockEntity 移除时调用） */
    public static void unregisterStation(Level level, BlockPos pos, StationType stationType) {
        if (level == null || pos == null || stationType == null) return;
        Map<StationType, CopyOnWriteArrayList<BlockPos>> typeMap = STATION_REGISTRY.get(level.dimension().location());
        if (typeMap == null) return;
        CopyOnWriteArrayList<BlockPos> list = typeMap.get(stationType);
        if (list != null) {
            list.remove(pos);
        }
    }

    /**
     * 查找指定维度中距离 pos 最近的指定类型货运站
     * 原理：用于订单生成时，从已加载的站点中选取真实目标位置
     *
     * @param level      维度
     * @param targetType  目标站点类型
     * @param excludePos  排除的位置（避免源站点和目标站点重合）
     * @return 最近的同类型站点 BlockPos，无可用时返回 null
     */
    public static BlockPos findNearestStationByType(Level level, StationType targetType, BlockPos excludePos) {
        if (level == null || targetType == null) return null;
        Map<StationType, CopyOnWriteArrayList<BlockPos>> typeMap = STATION_REGISTRY.get(level.dimension().location());
        if (typeMap == null) return null;
        CopyOnWriteArrayList<BlockPos> list = typeMap.get(targetType);
        if (list == null || list.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos stationPos : list) {
            if (excludePos != null && stationPos.equals(excludePos)) continue;
            double distSq = stationPos.distSqr(excludePos != null ? excludePos : BlockPos.ZERO);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = stationPos;
            }
        }
        return nearest;
    }

    /**
     * 获取指定维度中某类型的所有货运站位置
     * 原理：用于订单生成时枚举所有源站点；
     * 性能：返回原 CopyOnWriteArrayList 的不可变视图（Collections.unmodifiableList），不再每次 List.copyOf → 避免每次分配 ImmutableList + 数组，
     *       CopyOnWriteArrayList 本身就是"写时 snapshot 迭代"线程安全模型，调用方全都是只读遍历，不存在修改风险。
     */
    public static List<BlockPos> getStationsByType(Level level, StationType stationType) {
        if (level == null || stationType == null) return List.of();
        Map<StationType, CopyOnWriteArrayList<BlockPos>> typeMap = STATION_REGISTRY.get(level.dimension().location());
        if (typeMap == null) return List.of();
        CopyOnWriteArrayList<BlockPos> list = typeMap.get(stationType);
        return list != null && !list.isEmpty() ? java.util.Collections.unmodifiableList(list) : List.of();
    }

    /** 服务器停止时清理 */
    public static void clear() {
        GENERATOR_REGISTRY.clear();
        STATION_REGISTRY.clear();
        DETECTOR_REGISTRY.clear();
    }

    // ===== 检测器注册（CargoDetectorBlockEntity 使用） =====

    /** 注册货物检测器（BlockEntity 加载时调用） */
    public static void registerDetector(Level level, BlockPos pos, StationType stationType) {
        if (level == null || pos == null || stationType == null) return;
        DETECTOR_REGISTRY
                .computeIfAbsent(level.dimension().location(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(stationType, k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(pos);
        LOGGER.debug("[CargoDispatch] 注册检测器 @ {} dim={} type={}", pos, level.dimension().location(), stationType.getId());
    }

    /** 注销货物检测器（BlockEntity 移除时调用） */
    public static void unregisterDetector(Level level, BlockPos pos, StationType stationType) {
        if (level == null || pos == null || stationType == null) return;
        Map<StationType, CopyOnWriteArrayList<BlockPos>> typeMap = DETECTOR_REGISTRY.get(level.dimension().location());
        if (typeMap == null) return;
        CopyOnWriteArrayList<BlockPos> list = typeMap.get(stationType);
        if (list != null) {
            list.remove(pos);
        }
    }

    /**
     * 获取指定维度中某类型的所有货物检测器位置
     * @param level 维度
     * @param stationType 类型；若传 GENERIC，则返回所有类型检测器
     * 性能：不再每次分配 ImmutableList + 数组——CopyOnWriteArrayList 调用方只会遍历，直接返回 unmodifiable 视图。
     */
    public static List<BlockPos> getDetectorsByType(Level level, StationType stationType) {
        if (level == null) return List.of();
        Map<StationType, CopyOnWriteArrayList<BlockPos>> typeMap = DETECTOR_REGISTRY.get(level.dimension().location());
        if (typeMap == null) return List.of();
        if (stationType == StationType.GENERIC) {
            // 合并所有类型：只在有 >1 种类型时才临时分配 1 个 ArrayList（极少场景）
            java.util.List<CopyOnWriteArrayList<BlockPos>> allLists = new java.util.ArrayList<>(typeMap.values());
            if (allLists.isEmpty()) return List.of();
            if (allLists.size() == 1) {
                CopyOnWriteArrayList<BlockPos> l = allLists.get(0);
                return l != null && !l.isEmpty() ? java.util.Collections.unmodifiableList(l) : List.of();
            }
            // 多种类型：合并成 1 个 ArrayList（这种场景不可避免 1 次分配，但频率很低）
            java.util.ArrayList<BlockPos> merged = new java.util.ArrayList<>();
            for (CopyOnWriteArrayList<BlockPos> l : allLists) merged.addAll(l);
            return merged;
        }
        CopyOnWriteArrayList<BlockPos> list = typeMap.get(stationType);
        return list != null && !list.isEmpty() ? java.util.Collections.unmodifiableList(list) : List.of();
    }
}
