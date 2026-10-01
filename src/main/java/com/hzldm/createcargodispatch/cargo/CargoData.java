package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 货运数据：记录货物目标坐标、维度、订单ID
 *
 * 原理：
 *  - 不可变数据对象，通过 NBT 序列化存储到 BlockEntity
 *  - 目标坐标：货物要送达的位置
 *  - 目标维度：货物要送达的维度 ID
 *  - 订单ID：关联接单系统的订单
 */
public class CargoData {

    private BlockPos targetPos = BlockPos.ZERO;
    private ResourceLocation targetDimension = null;
    private String orderId = "";
    /** 运输类型：land（陆运）、space（太空运）、submarine（潜水艇运） */
    private String transportType = "land";
    /** 起始站类型（货物来源） */
    private String sourceStationType = "generic";
    /** 目标站类型（货物目的地，检测器必须匹配此类型） */
    private String targetStationType = "generic";
    /** 目标站编号（货物只能在该编号站点提交；空串=旧数据，按类型宽松匹配） */
    private String targetStationId = "";
    /** 货箱三维尺寸（决定结构方块数/模型缩放/容量）；旧数据缺省为标准 3×3×9 */
    private CargoDimensions dimensions = CargoDimensions.DEFAULT;

    public CargoData() {
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    public void setTargetPos(BlockPos targetPos) {
        this.targetPos = targetPos;
    }

    public ResourceLocation getTargetDimension() {
        return targetDimension;
    }

    public void setTargetDimension(ResourceLocation targetDimension) {
        this.targetDimension = targetDimension;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getTransportType() {
        return transportType;
    }

    public void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    public String getSourceStationType() {
        return sourceStationType;
    }

    public void setSourceStationType(String sourceStationType) {
        this.sourceStationType = sourceStationType;
    }

    public String getTargetStationType() {
        return targetStationType;
    }

    public void setTargetStationType(String targetStationType) {
        this.targetStationType = targetStationType;
    }

    public String getTargetStationId() {
        return targetStationId != null ? targetStationId : "";
    }

    public void setTargetStationId(String targetStationId) {
        this.targetStationId = targetStationId != null ? targetStationId : "";
    }

    public CargoDimensions getDimensions() {
        return dimensions != null ? dimensions : CargoDimensions.DEFAULT;
    }

    public void setDimensions(CargoDimensions dimensions) {
        this.dimensions = dimensions != null ? dimensions : CargoDimensions.DEFAULT;
    }

    /** 是否有有效的货运目标 */
    public boolean hasTarget() {
        return targetDimension != null && !targetPos.equals(BlockPos.ZERO);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("TargetX", targetPos.getX());
        tag.putInt("TargetY", targetPos.getY());
        tag.putInt("TargetZ", targetPos.getZ());
        if (targetDimension != null) {
            tag.putString("TargetDim", targetDimension.toString());
        }
        tag.putString("OrderId", orderId);
        tag.putString("TransportType", transportType);
        tag.putString("SourceStationType", sourceStationType);
        tag.putString("TargetStationType", targetStationType);
        if (targetStationId != null && !targetStationId.isEmpty()) {
            tag.putString("TargetStationId", targetStationId);
        }
        tag.put("Dims", dimensions.save());
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null) {
            return;
        }
        this.targetPos = new BlockPos(
                tag.getInt("TargetX"),
                tag.getInt("TargetY"),
                tag.getInt("TargetZ")
        );
        if (tag.contains("TargetDim")) {
            this.targetDimension = ResourceLocation.parse(tag.getString("TargetDim"));
        }
        this.orderId = tag.getString("OrderId");
        this.transportType = tag.getString("TransportType");
        this.sourceStationType = tag.getString("SourceStationType");
        this.targetStationType = tag.getString("TargetStationType");
        // 旧货箱 NBT 无 TargetStationId → 空串（按类型宽松匹配，兼容）
        this.targetStationId = tag.contains("TargetStationId") ? tag.getString("TargetStationId") : "";
        // 旧货箱无 Dims → 默认标准尺寸
        this.dimensions = tag.contains("Dims")
                ? CargoDimensions.load(tag.getCompound("Dims")) : CargoDimensions.DEFAULT;
    }
}
