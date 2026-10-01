package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 货运订单数据
 *
 * 原理：
 *  - 不可变数据对象，支持 NBT 序列化
 *  - 起点为生成器多方块结构位置，终点为送货目标
 *  - 状态机：PENDING → ACCEPTED → IN_TRANSIT → COMPLETED / CANCELLED
 *  - 接单玩家记录便于追溯责任
 */
public class OrderData {

    public enum Status {
        PENDING, ACCEPTED, IN_TRANSIT, COMPLETED, CANCELLED
    }

    private String orderId;
    private BlockPos startPos;
    private ResourceLocation startDimension;
    private BlockPos targetPos;
    private ResourceLocation targetDimension;
    private TransportType transportType;
    private Status status;
    private UUID acceptedPlayer;
    /** 货物内容：物品ID（命名空间:路径） */
    private String cargoItemId;
    /** 货物数量 */
    private int cargoCount;
    /** 货物价值（用于奖励计算） */
    private int reward;
    /** 货运站类型（决定订单货物种类，即起始站类型） */
    private StationType stationType = StationType.GENERIC;
    /** 目标站类型（货物要运到的站点类型，不能与 stationType 相同） */
    private StationType targetStationType = StationType.GENERIC;
    /** 目标站编号（同类型多站时，货物只能提交到该编号站点；空串=旧数据，按类型宽松匹配） */
    private String targetStationId = "";
    /** 订单创建时的游戏时间（tick），用于刷新计算 */
    private long createdGameTime = 0;
    /**
     * 需求3：订单「未接」过期时间戳（绝对游戏 tick），level.getGameTime() >= expireAtGameTime && status==PENDING 即视为过期
     *  - 生成订单时赋值：createdGameTime + ThreadLocalRandom(min~max)，其中范围由 ModConfig ORDER_EXPIRE_MIN/MAX 决定
     *  - ACCEPTED/IN_TRANSIT/COMPLETED 订单忽略该字段（只限制未接单）
     *  - 默认 0 代表"永不过期"（老存档、/ccd generateOrder 指令生成的订单若没赋值也兼容）
     */
    private long expireAtGameTime = 0L;
    /** 物理化 SubLevel 的 UUID（接单装配后设置，用于精确删除该订单的货箱） */
    private UUID subLevelUuid = null;
    /**
     * 订单属主（联合运输系统）：
     *  - ownerPlayer  非空 = 独立玩家订单，仅该玩家可见/可接
     *  - ownerCompany 非空 = 联合运输公司订单，全体成员共享
     *  - 两者皆空 = 旧存档订单，沿用旧的「按已连接站点过滤」全局可见规则
     * 接单后 acceptedPlayer 记录实际配送成员，属主字段不随接单变化。
     */
    private UUID ownerPlayer = null;
    private UUID ownerCompany = null;
    /**
     * 调试手工订单标记：由调试货箱放置后自动登记（非公共订单池产生）。
     * 取消时永久删除（连同货箱一起移除），不放回 PENDING 池。
     */
    private boolean manualDebug = false;

    public OrderData() {
        this.status = Status.PENDING;
        this.transportType = TransportType.LAND;
    }

    /** 货箱三维尺寸（生成时从始发站配置读取，决定结构/数量/价格）；旧数据默认标准 */
    private CargoDimensions dimensions = CargoDimensions.DEFAULT;

    public OrderData(String orderId, BlockPos startPos, ResourceLocation startDim,
                    BlockPos targetPos, ResourceLocation targetDim,
                    TransportType type, String cargoItemId, int cargoCount, int reward,
                    StationType stationType, StationType targetStationType, long createdGameTime) {
        this.orderId = orderId;
        this.startPos = startPos;
        this.startDimension = startDim;
        this.targetPos = targetPos;
        this.targetDimension = targetDim;
        this.transportType = type;
        this.status = Status.PENDING;
        this.cargoItemId = cargoItemId;
        this.cargoCount = cargoCount;
        this.reward = reward;
        this.stationType = stationType;
        this.targetStationType = targetStationType;
        this.createdGameTime = createdGameTime;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public BlockPos getStartPos() {
        return startPos;
    }

    public void setStartPos(BlockPos startPos) {
        this.startPos = startPos;
    }

    /** 获取物理化 SubLevel 的 UUID（用于精确删除该订单的货箱） */
    public UUID getSubLevelUuid() {
        return subLevelUuid;
    }

    /** 设置物理化 SubLevel 的 UUID（装配成功后调用） */
    public void setSubLevelUuid(UUID subLevelUuid) {
        this.subLevelUuid = subLevelUuid;
    }

    public UUID getOwnerPlayer() {
        return ownerPlayer;
    }

    public void setOwnerPlayer(UUID ownerPlayer) {
        this.ownerPlayer = ownerPlayer;
    }

    public UUID getOwnerCompany() {
        return ownerCompany;
    }

    public void setOwnerCompany(UUID ownerCompany) {
        this.ownerCompany = ownerCompany;
    }

    public ResourceLocation getStartDimension() {
        return startDimension;
    }

    public void setStartDimension(ResourceLocation startDimension) {
        this.startDimension = startDimension;
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

    public TransportType getTransportType() {
        return transportType;
    }

    public void setTransportType(TransportType transportType) {
        this.transportType = transportType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public UUID getAcceptedPlayer() {
        return acceptedPlayer;
    }

    public void setAcceptedPlayer(UUID acceptedPlayer) {
        this.acceptedPlayer = acceptedPlayer;
    }

    public String getCargoItemId() {
        return cargoItemId;
    }

    public void setCargoItemId(String cargoItemId) {
        this.cargoItemId = cargoItemId;
    }

    public int getCargoCount() {
        return cargoCount;
    }

    public void setCargoCount(int cargoCount) {
        this.cargoCount = cargoCount;
    }

    public int getReward() {
        return reward;
    }

    public void setReward(int reward) {
        this.reward = reward;
    }

    public StationType getStationType() {
        return stationType;
    }

    public void setStationType(StationType stationType) {
        this.stationType = stationType;
    }

    public StationType getTargetStationType() {
        return targetStationType;
    }

    public void setTargetStationType(StationType targetStationType) {
        this.targetStationType = targetStationType;
    }

    public String getTargetStationId() {
        return targetStationId != null ? targetStationId : "";
    }

    public void setTargetStationId(String targetStationId) {
        this.targetStationId = targetStationId != null ? targetStationId : "";
    }

    public long getCreatedGameTime() {
        return createdGameTime;
    }

    public void setCreatedGameTime(long createdGameTime) {
        this.createdGameTime = createdGameTime;
    }

    public long getExpireAtGameTime() { return expireAtGameTime; }

    public void setExpireAtGameTime(long expireAtGameTime) { this.expireAtGameTime = expireAtGameTime; }

    /**
     * 计算订单剩余可接取的秒数（四舍五入，向上取整避免 UI 跳到 0 但实际还 0.9s）
     * - 若 expireAtGameTime <= 0（永不过期老订单）→ 返回 -1，UI 显示 "∞"
     * - 若已经过期 → 返回 0
     * @param currentGameTime 当前游戏 tick（level.getGameTime()）
     */
    public int getRemainingSeconds(long currentGameTime) {
        if (expireAtGameTime <= 0L) return -1;
        long remainTicks = expireAtGameTime - currentGameTime;
        if (remainTicks <= 0L) return 0;
        return (int) Math.ceil(remainTicks / 20.0D);
    }

    /** 把 remainingSeconds 格式化成 "mm:ss" / "∞" 的 UI 文本（订单 Tab / 聊天栏共用） */
    public static String formatRemainingTime(int remainingSeconds) {
        if (remainingSeconds < 0) return "∞";
        int m = remainingSeconds / 60;
        int s = remainingSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    /** 是否可接单 */
    public boolean isAcceptable() {
        return status == Status.PENDING;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", orderId);
        if (startPos != null) {
            tag.putInt("StartX", startPos.getX());
            tag.putInt("StartY", startPos.getY());
            tag.putInt("StartZ", startPos.getZ());
        }
        if (startDimension != null) {
            tag.putString("StartDim", startDimension.toString());
        }
        if (targetPos != null) {
            tag.putInt("TargetX", targetPos.getX());
            tag.putInt("TargetY", targetPos.getY());
            tag.putInt("TargetZ", targetPos.getZ());
        }
        if (targetDimension != null) {
            tag.putString("TargetDim", targetDimension.toString());
        }
        tag.putString("TransportType", transportType.getId());
        tag.putString("Status", status.name());
        if (acceptedPlayer != null) {
            tag.putUUID("AcceptedPlayer", acceptedPlayer);
        }
        if (cargoItemId != null) {
            tag.putString("CargoItemId", cargoItemId);
        }
        tag.putInt("CargoCount", cargoCount);
        tag.putInt("Reward", reward);
        tag.putString("StationType", stationType.getId());
        tag.putString("TargetStationType", targetStationType.getId());
        if (targetStationId != null && !targetStationId.isEmpty()) {
            tag.putString("TargetStationId", targetStationId);
        }
        tag.putLong("CreatedGameTime", createdGameTime);
        tag.putLong("ExpireAtGameTime", expireAtGameTime);
        if (subLevelUuid != null) {
            tag.putUUID("SubLevelUuid", subLevelUuid);
        }
        // 联合运输属主（二选一，皆空=旧存档全局订单）
        if (ownerPlayer != null) {
            tag.putUUID("OwnerPlayer", ownerPlayer);
        }
        if (ownerCompany != null) {
            tag.putUUID("OwnerCompany", ownerCompany);
        }
        tag.putBoolean("ManualDebug", manualDebug);
        tag.put("Dims", getDimensions().save());
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null) return;
        orderId = tag.getString("OrderId");
        if (tag.contains("StartX")) {
            startPos = new BlockPos(tag.getInt("StartX"), tag.getInt("StartY"), tag.getInt("StartZ"));
        }
        if (tag.contains("StartDim")) {
            startDimension = ResourceLocation.parse(tag.getString("StartDim"));
        }
        if (tag.contains("TargetX")) {
            targetPos = new BlockPos(tag.getInt("TargetX"), tag.getInt("TargetY"), tag.getInt("TargetZ"));
        }
        if (tag.contains("TargetDim")) {
            targetDimension = ResourceLocation.parse(tag.getString("TargetDim"));
        }
        transportType = TransportType.byId(tag.getString("TransportType"));
        try {
            status = Status.valueOf(tag.getString("Status"));
        } catch (IllegalArgumentException e) {
            status = Status.PENDING;
        }
        if (tag.hasUUID("AcceptedPlayer")) {
            acceptedPlayer = tag.getUUID("AcceptedPlayer");
        }
        cargoItemId = tag.getString("CargoItemId");
        cargoCount = tag.getInt("CargoCount");
        reward = tag.getInt("Reward");
        stationType = StationType.byId(tag.getString("StationType"));
        targetStationType = StationType.byId(tag.getString("TargetStationType"));
        // 旧存档无 TargetStationId → 空串（按类型宽松匹配，兼容）
        targetStationId = tag.contains("TargetStationId") ? tag.getString("TargetStationId") : "";
        createdGameTime = tag.getLong("CreatedGameTime");
        // ExpireAtGameTime 是新增字段：老存档若无该 key → 默认 0（永不过期，兼容）
        expireAtGameTime = tag.contains("ExpireAtGameTime") ? tag.getLong("ExpireAtGameTime") : 0L;
        if (tag.hasUUID("SubLevelUuid")) {
            subLevelUuid = tag.getUUID("SubLevelUuid");
        }
        // 联合运输属主：老存档无字段 → null（按旧全局订单规则兼容）
        if (tag.hasUUID("OwnerPlayer")) {
            ownerPlayer = tag.getUUID("OwnerPlayer");
        }
        if (tag.hasUUID("OwnerCompany")) {
            ownerCompany = tag.getUUID("OwnerCompany");
        }
        manualDebug = tag.getBoolean("ManualDebug");
        this.dimensions = tag.contains("Dims")
                ? CargoDimensions.load(tag.getCompound("Dims")) : CargoDimensions.DEFAULT;
    }

    public CargoDimensions getDimensions() {
        return dimensions != null ? dimensions : CargoDimensions.DEFAULT;
    }

    public void setDimensions(CargoDimensions dimensions) {
        this.dimensions = dimensions != null ? dimensions : CargoDimensions.DEFAULT;
    }

    public boolean isManualDebug() {
        return manualDebug;
    }

    public void setManualDebug(boolean manualDebug) {
        this.manualDebug = manualDebug;
    }
}
