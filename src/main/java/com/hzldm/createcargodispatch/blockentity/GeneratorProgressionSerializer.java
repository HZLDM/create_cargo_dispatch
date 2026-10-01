package com.hzldm.createcargodispatch.blockentity;

import net.minecraft.nbt.CompoundTag;

/**
 * 生成器出货状态 NBT：模式、红框显示。
 *
 * <p>SRP：从 CargoGeneratorBlockEntity 抽出，BE 不混杂自己的序列化细节。
 */
public final class GeneratorProgressionSerializer {

    private GeneratorProgressionSerializer() {}

    /** 保存出货状态 */
    public static void save(CompoundTag tag, GeneratorSpawnMode mode, boolean showRange) {
        tag.putString("SpawnMode", mode.name());
        tag.putBoolean("ShowRange", showRange);
    }

    /** 读取模式（无字段/非法值回退 GROUND） */
    public static GeneratorSpawnMode readMode(CompoundTag tag) {
        return tag.contains("SpawnMode") ? GeneratorSpawnMode.byName(tag.getString("SpawnMode"))
                : GeneratorSpawnMode.GROUND;
    }

    /** 读取红框显示标记 */
    public static boolean readShowRange(CompoundTag tag) {
        return tag.getBoolean("ShowRange");
    }
}
