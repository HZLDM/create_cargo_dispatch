package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 货物 SubLevel 持久化存储（SavedData）
 *
 * 原理：
 *  - Sable SubLevel 本身由 Sable 持久化，但 CargoManager 的内存缓存会丢失
 *  - 服务器重启后需要恢复以下映射：
 *    - SubLevel UUID → startPos
 *    - SubLevel UUID → blocks 列表
 *    - SubLevel UUID → cargoData
 *    - SubLevel UUID → inventory 快照
 *    - SubLevel UUID → 质量
 *    - orderId → SubLevel UUID
 *  - 重启后通过扫描存活 SubLevel 重建映射，过滤已删除的 SubLevel
 *
 * 存储结构：
 *  - Entries: List<Entry NBT>
 *    每个 Entry：UUID, startPos, blocks, cargoData, snapshot, mass, orderId
 */
public class CargoSubLevelStore extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-SubLevelStore");
    private static final String DATA_NAME = "create_cargo_dispatch_sublevels";

    public CargoSubLevelStore() {
    }

    /** 从 NBT 加载 */
    public static CargoSubLevelStore load(CompoundTag tag, HolderLookup.Provider registries) {
        CargoSubLevelStore store = new CargoSubLevelStore();
        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        int restored = 0;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            try {
                UUID uuid = entry.getUUID("UUID");
                BlockPos startPos = new BlockPos(
                        entry.getInt("StartX"), entry.getInt("StartY"), entry.getInt("StartZ"));

                // 加载 blocks 列表
                List<BlockPos> blocks = new ArrayList<>();
                ListTag blockList = entry.getList("Blocks", Tag.TAG_COMPOUND);
                for (int j = 0; j < blockList.size(); j++) {
                    CompoundTag bTag = blockList.getCompound(j);
                    blocks.add(new BlockPos(bTag.getInt("X"), bTag.getInt("Y"), bTag.getInt("Z")));
                }

                // 加载 cargoData
                CargoData cargoData = new CargoData();
                if (entry.contains("CargoData")) {
                    cargoData.load(entry.getCompound("CargoData"), registries);
                }

                // 加载 inventory 快照
                List<ItemStack> snapshot = new ArrayList<>();
                if (entry.contains("Snapshot")) {
                    ListTag snapList = entry.getList("Snapshot", Tag.TAG_COMPOUND);
                    for (int j = 0; j < snapList.size(); j++) {
                        ItemStack stack = ItemStack.parseOptional(registries, snapList.getCompound(j));
                        if (!stack.isEmpty()) {
                            snapshot.add(stack);
                        }
                    }
                }

                double mass = entry.contains("Mass") ? entry.getDouble("Mass") : -1.0;
                boolean attached = entry.getBoolean("Attached");

                // 恢复到 CargoManager 内存
                CargoManager.restoreFromSave(uuid, startPos, blocks, cargoData, snapshot, mass, attached);
                restored++;
            } catch (Exception e) {
                LOGGER.warn("[CargoDispatch] 加载 SubLevel 条目失败: {}", e.getMessage());
            }
        }
        LOGGER.info("[CargoDispatch] 加载 SubLevel 存储：恢复 {} 个条目", restored);
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = CargoManager.saveForPersistence(registries);
        tag.put("Entries", entries);
        LOGGER.info("[CargoDispatch] 保存 SubLevel 存储：{} 个条目", entries.size());
        return tag;
    }

    /** 获取存储（不存在则创建） */
    public static CargoSubLevelStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CargoSubLevelStore::new, CargoSubLevelStore::load),
                DATA_NAME
        );
    }

    /** 标记为已修改 */
    public static void markDirty(ServerLevel level) {
        CargoSubLevelStore store = get(level);
        store.setDirty();
    }
}
