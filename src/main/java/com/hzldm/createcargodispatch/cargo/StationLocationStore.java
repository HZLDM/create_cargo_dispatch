package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 货运站位置持久化存储（SavedData）
 *
 * 原理：
 *  - 使用 NeoForge 的 SavedData 机制，把所有已发现的货运站位置持久化到维度数据文件
 *  - CargoStationBlockEntity.onLoad 时写入，即使 chunk 卸载后位置仍然保留
 *  - 服务器重启后从磁盘恢复，玩家不需要重新探索已发现的站点
 *  - 订单生成时从该存储查询真实站点坐标，支持跨远距离配对
 *  - getStations/getStationWithVerification 返回前都会验证 BlockEntity 仍存在（防止拆站后
 *    SavedData 里残留的旧坐标还在生成订单）
 *
 * 存储结构：
 *  - type -> List<Long>（BlockPos.asLong() 压缩，节省磁盘空间）
 *  - 每个维度独立存储一份（StationLocationStore.get(level) 按维度获取）
 */
public class StationLocationStore extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-StationStore");
    private static final String DATA_NAME = "create_cargo_dispatch_stations";

    /** type → 位置列表（BlockPos.asLong 压缩） */
    private final Map<String, CopyOnWriteArrayList<Long>> stations = new ConcurrentHashMap<>();

    public StationLocationStore() {
    }

    /** 从 NBT 加载 */
    public static StationLocationStore load(CompoundTag tag, HolderLookup.Provider registries) {
        StationLocationStore store = new StationLocationStore();
        ListTag list = tag.getList("Stations", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String typeId = entry.getString("Type");
            long[] positions = entry.getLongArray("Positions");
            CopyOnWriteArrayList<Long> posList = new CopyOnWriteArrayList<>();
            for (long pos : positions) {
                posList.add(pos);
            }
            store.stations.put(typeId, posList);
        }
        LOGGER.info("[CargoDispatch] 加载站点存储：{} 种类型", store.stations.size());
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<String, CopyOnWriteArrayList<Long>> entry : stations.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("Type", entry.getKey());
            long[] positions = new long[entry.getValue().size()];
            int i = 0;
            for (long pos : entry.getValue()) {
                positions[i++] = pos;
            }
            e.putLongArray("Positions", positions);
            list.add(e);
        }
        tag.put("Stations", list);
        return tag;
    }

    /** 获取指定维度的站点存储（不存在则创建） */
    public static StationLocationStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StationLocationStore::new, StationLocationStore::load),
                DATA_NAME
        );
    }

    /** 注册站点（onLoad 时调用） */
    public void addStation(StationType type, BlockPos pos) {
        if (type == null || pos == null) return;
        stations.computeIfAbsent(type.getId(), k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(pos.asLong());
        setDirty();
    }

    /** 注销站点（BlockEvent.BreakEvent 破坏方块时调用，持久化清理） */
    public void removeStation(StationType type, BlockPos pos) {
        if (type == null || pos == null) return;
        CopyOnWriteArrayList<Long> list = stations.get(type.getId());
        if (list != null) {
            if (list.remove(pos.asLong())) {
                setDirty();
            }
        }
    }

    /**
     * 校验给定位置的 CargoStationBlockEntity 是否真实存在
     *
     * 性能关键：**不强制加载 chunk**（getBlockEntity 会强制加载，会引发卡顿）。
     * 若 chunk 当前未加载 → 返回 true（保守假设站仍然存在，chunk 卸载是正常行为）。
     * 这样 SavedData 里即使有几十个站分散在不同 chunk，也不会级联加载造成卡死。
     * 只有玩家在站附近、chunk 已加载时才真正校验 BlockEntity。
     */
    public boolean stationBlockEntityExists(ServerLevel level, StationType type, BlockPos pos) {
        if (level == null || type == null || pos == null) return false;
        // chunk 未加载：不强制 getBlockEntity，保守认为站存在（避免卡死）
        if (!level.isLoaded(pos)) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        // 1) 优先匹配：该位置是货运站方块（CargoStationBlockEntity）且类型一致
        if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE
                && stationBE.getStationType() == type) {
            return true;
        }
        // 2) 兼容匹配：订单里保存的 pos 很可能是「检测器位置」而非货运站方块位置
        //    （连接时 searchNearbyDetector 找到检测器优先用 detectorPos 作为连接坐标写入订单）
        //    这时该位置放的是 CargoDetectorBlockEntity，它的 getStationType() 也是和 type 相等的
        if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity detectorBE
                && detectorBE.getStationType() == type) {
            return true;
        }
        // 3) 兼容匹配：位置=货物生成器（CargoGeneratorBlockEntity），生成器和站/检测器同结构，类型一致也视为站存在
        if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity generatorBE
                && generatorBE.getStationType() == type) {
            return true;
        }
        return false;
    }

    /**
     * 获取指定类型的所有真实存在的站点（带 BlockEntity 校验，但不强制加载 chunk）
     * 性能：chunk 未加载的站暂时保留在列表里（当作存在，不 prune），避免 SavedData 被误删；
     *       只有 chunk 已加载且 BlockEntity 确实不存在的，才惰性 prune。
     */
    public List<BlockPos> getStationsVerified(ServerLevel level, StationType type) {
        if (type == null) return List.of();
        CopyOnWriteArrayList<Long> list = stations.get(type.getId());
        if (list == null || list.isEmpty()) return List.of();
        List<BlockPos> result = new ArrayList<>(list.size());
        List<Long> toRemove = null;
        for (long packed : list) {
            BlockPos pos = BlockPos.of(packed);
            if (level != null && level.isLoaded(pos)) {
                // chunk 已加载：真正校验 BlockEntity（这时 getBlockEntity 不卡）
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE
                        && stationBE.getStationType() == type) {
                    result.add(pos);
                } else {
                    // 真的被拆了（chunk已加载但BlockEntity不存在/类型不匹配）
                    if (toRemove == null) toRemove = new ArrayList<>(4);
                    toRemove.add(packed);
                }
            } else {
                // chunk 未加载：保守保留（当作站存在，不prune也不查BE）
                result.add(pos);
            }
        }
        if (toRemove != null && !toRemove.isEmpty()) {
            list.removeAll(toRemove);
            setDirty();
            LOGGER.info("[CargoDispatch] 站点惰性清理：{} 已从 type={} 移除（对应chunk已加载但BlockEntity不存在）",
                    toRemove.size(), type.getId());
        }
        return result;
    }

    /** 查找指定类型中距离参考点最近、且 BlockEntity 真实存在的站点（可排除自身） */
    public BlockPos findNearestVerified(ServerLevel level, StationType type, BlockPos reference, BlockPos exclude) {
        if (level == null || type == null || reference == null) return null;
        List<BlockPos> candidates = getStationsVerified(level, type);
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : candidates) {
            if (exclude != null && pos.equals(exclude)) continue;
            double distSq = pos.distSqr(reference);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }
        return nearest;
    }
}
