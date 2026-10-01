package com.hzldm.createcargodispatch.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 货箱连接器状态 NBT 序列化。
 *
 * <p>持久化：吸附态、载具/货箱 UUID、货箱原始位姿（断电解约后传送回归用）。
 * 约束句柄为运行时物理对象，不持久化；重载后由 BE 校正吸附标记。
 */
public final class CargoConnectorSerializer {

    private CargoConnectorSerializer() {}

    /** 连接器吸附状态 */
    public static final class State {
        public boolean attached;
        public UUID vehicleUuid;
        public UUID cargoUuid;
        /** 货箱原始锚点（信息用途） */
        public BlockPos originalAnchor;
        /** 货箱原始世界位置 */
        public double[] originalCargoPos;
        /** 货箱原始朝向 (x, y, z, w) */
        public double[] originalCargoQuat;
        /** 运行时：固定约束句柄（不持久化） */
        public transient Object constraintHandle;

        /** 重置为空闲（货箱删除路径统一调用；公开给 cargo 包的 CargoDetacher） */
        public void clear() {
            attached = false;
            vehicleUuid = null;
            cargoUuid = null;
            originalAnchor = null;
            originalCargoPos = null;
            originalCargoQuat = null;
            constraintHandle = null;
        }
    }

    static void save(CompoundTag tag, State s) {
        tag.putBoolean("Attached", s.attached);
        if (s.vehicleUuid != null) tag.putUUID("VehicleUuid", s.vehicleUuid);
        if (s.cargoUuid != null) tag.putUUID("CargoUuid", s.cargoUuid);
        if (s.originalAnchor != null) {
            tag.putInt("AnchorX", s.originalAnchor.getX());
            tag.putInt("AnchorY", s.originalAnchor.getY());
            tag.putInt("AnchorZ", s.originalAnchor.getZ());
        }
        if (s.originalCargoPos != null && s.originalCargoPos.length == 3) {
            tag.putDouble("OrigPosX", s.originalCargoPos[0]);
            tag.putDouble("OrigPosY", s.originalCargoPos[1]);
            tag.putDouble("OrigPosZ", s.originalCargoPos[2]);
        }
        if (s.originalCargoQuat != null && s.originalCargoQuat.length == 4) {
            tag.putDouble("OrigQuatX", s.originalCargoQuat[0]);
            tag.putDouble("OrigQuatY", s.originalCargoQuat[1]);
            tag.putDouble("OrigQuatZ", s.originalCargoQuat[2]);
            tag.putDouble("OrigQuatW", s.originalCargoQuat[3]);
        }
    }

    static State load(CompoundTag tag) {
        State s = new State();
        s.attached = tag.getBoolean("Attached");
        s.vehicleUuid = tag.hasUUID("VehicleUuid") ? tag.getUUID("VehicleUuid") : null;
        s.cargoUuid = tag.hasUUID("CargoUuid") ? tag.getUUID("CargoUuid") : null;
        if (tag.contains("AnchorX")) {
            s.originalAnchor = new BlockPos(tag.getInt("AnchorX"), tag.getInt("AnchorY"), tag.getInt("AnchorZ"));
        }
        if (tag.contains("OrigPosX")) {
            s.originalCargoPos = new double[]{
                    tag.getDouble("OrigPosX"), tag.getDouble("OrigPosY"), tag.getDouble("OrigPosZ")};
        }
        if (tag.contains("OrigQuatX")) {
            s.originalCargoQuat = new double[]{
                    tag.getDouble("OrigQuatX"), tag.getDouble("OrigQuatY"),
                    tag.getDouble("OrigQuatZ"), tag.getDouble("OrigQuatW")};
        }
        return s;
    }
}
