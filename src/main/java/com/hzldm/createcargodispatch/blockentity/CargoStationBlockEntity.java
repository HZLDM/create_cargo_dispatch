package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.CargoGeneratorRegistry;
import com.hzldm.createcargodispatch.cargo.StationLocationStore;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 货运站 BlockEntity（GENERIC 通用站，4 种专属类型继承此类）
 *
 * 职责：
 *  - 提供订单 UI 入口（CargoStationBlock.openMenu → CargoGeneratorMenu）
 *  - 维护 stationType / stationId（结构推断或子类构造时设置）
 *  - 维护 autoSubmit 开关（提交 Tab 客户端切换，服务端持久化 + 同步）
 *  - 提供 scanNearbySubmittableCargos（扫描附近可提交货箱供提交 Tab 显示）
 *  - 通知附近检测器 invalidate 缓存（autoSubmit 状态变更时触发）
 *
 * 子类：
 *  - LumberYardStationBlockEntity：stationType=LUMBER_YARD
 *  - MineStationBlockEntity：stationType=MINE
 *  - FarmStationBlockEntity：stationType=FARM
 *  - PastureStationBlockEntity：stationType=PASTURE
 */
public class CargoStationBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Station");

    /** 货运站类型 */
    protected StationType stationType = StationType.GENERIC;
    /** 货运站编号（同一结构内的站/生成器/检测器共享） */
    protected String stationId = "";
    /** 自动提交开关（默认关闭，需玩家手动开启） */
    protected boolean autoSubmit = false;
    /** 新生成货箱的尺寸配置（偏好），只影响配置后的新订单/货箱；默认标准 3×3×9 */
    protected com.hzldm.createcargodispatch.cargo.CargoDimensions configuredDimensions =
            com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;

    /** 提交列表缓存（避免 UI 2 秒轮询导致重复扫描） */
    @Nullable
    private List<SubmittableCargo> cachedSubmitList = null;
    private long cachedSubmitListTick = -1L;
    private static final long SUBMIT_LIST_CACHE_TICKS = 40L;

    // ------------------------------------------------------------------
    // 构造函数
    // ------------------------------------------------------------------

    /** 子类构造：传入具体 BlockEntityType */
    protected CargoStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** GENERIC 公共构造（由 CargoStationBlock.newBlockEntity / ModBlockEntities 注册使用） */
    public CargoStationBlockEntity(BlockPos pos, BlockState state) {
        this(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO_STATION.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // 属性 getter / setter
    // ------------------------------------------------------------------

    public StationType getStationType() { return stationType; }

    public void setStationType(StationType type) {
        this.stationType = type;
        setChanged();
    }

    public String getStationId() { return stationId; }

    public void setStationId(String id) {
        this.stationId = id;
        setChanged();
    }

    public boolean isAutoSubmit() { return autoSubmit; }

    /**
     * 设置自动提交开关
     * 原理：
     *  - 变更后立即标记 dirty + 同步客户端数据包
     *  - 调用 invalidateNearbyDetectorCaches() 通知附近检测器清缓存
     *    （检测器缓存的 isBoundStationAutoSubmit 结果需要立即失效，否则 5 秒内还按旧值判断）
     */
    public void setAutoSubmit(boolean on) {
        if (this.autoSubmit == on) return;
        this.autoSubmit = on;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            invalidateNearbyDetectorCaches();
        }
    }

    public com.hzldm.createcargodispatch.cargo.CargoDimensions getConfiguredDimensions() {
        return configuredDimensions != null
                ? configuredDimensions
                : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
    }

    /**
     * 设置新货箱尺寸偏好（配置页调用）。仅影响之后新生成的货箱，已存在货箱不变。
     */
    public void setConfiguredDimensions(com.hzldm.createcargodispatch.cargo.CargoDimensions dims) {
        this.configuredDimensions = dims != null
                ? dims : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------
    // 生命周期：onLoad / setRemoved
    // ------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        StationTypeResolver.Result res = StationTypeResolver.resolveIfNeeded(this, stationType, stationId);
        if (res != null) {
            this.stationType = res.stationType();
            this.stationId = res.stationId();
            setChanged();
            // 编号是在 onLoad 中新解析出来的：主动推送 BE 更新，客户端（GUI 编号显示/Jade）才能立即拿到
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.registerStation(level, getBlockPos(), stationType);
            if (level instanceof ServerLevel serverLevel) {
                StationLocationStore store = StationLocationStore.get(serverLevel);
                store.addStation(stationType, getBlockPos());
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.unregisterStation(level, getBlockPos(), stationType);
        }
        super.setRemoved();
    }

    // ------------------------------------------------------------------
    // NBT 持久化 + 客户端同步
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("StationType", stationType.getId());
        tag.putString("StationId", stationId != null ? stationId : "");
        tag.putBoolean("AutoSubmit", autoSubmit);
        tag.put("CargoDims", getConfiguredDimensions().save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        String typeId = tag.getString("StationType");
        this.stationType = StationType.byId(typeId);
        this.stationId = tag.contains("StationId") ? tag.getString("StationId") : "";
        this.autoSubmit = tag.contains("AutoSubmit") && tag.getBoolean("AutoSubmit");
        this.configuredDimensions = tag.contains("CargoDims")
                ? com.hzldm.createcargodispatch.cargo.CargoDimensions.load(tag.getCompound("CargoDims"))
                : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------
    // MenuProvider（右键打开菜单）
    // ------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("create_cargo_dispatch.menu.cargo_station");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id,
            net.minecraft.world.entity.player.Inventory inv, Player player) {
        return new com.hzldm.createcargodispatch.menu.CargoGeneratorMenu(id, inv, this);
    }

    // ==================================================================
    // 提交 Tab 相关：扫描可提交货箱 + 缓存管理
    // ==================================================================

    /**
     * 提交页货箱条目（内部记录类）
     *
     * @param displayItems         前 5 个物品图标（Screen 渲染用）
     * @param subLevelUuid         SubLevel UUID（静态货箱为 null）
     * @param controllerPos        静态货箱主方块位置（SubLevel 为 null）
     * @param detectorPos          接收该货箱的检测器位置（提交按钮定位用）
     * @param totalItemCount       货箱内物品总数
     * @param orderId              关联订单号（用于显示来源站类型 + 订单号）
     * @param sourceStationTypeId  源站类型 ID（用于显示"伐木场订单 #xxx"）
     */
    public record SubmittableCargo(
            List<ItemStack> displayItems,
            @Nullable UUID subLevelUuid,
            @Nullable BlockPos controllerPos,
            BlockPos detectorPos,
            int totalItemCount,
            String orderId,
            String sourceStationTypeId
    ) {}

    /**
     * 使当前站的提交列表缓存失效
     * 调用时机：force=true 的 sendSubmitList / 手动提交完成后 / autoSubmit 切换后
     */
    public void invalidateSubmitListCache() {
        this.cachedSubmitList = null;
        this.cachedSubmitListTick = -1L;
    }

    /** 附近是否已连接同组货物生成器（编号/类型匹配且在绑定半径内） */
    public boolean hasNearbyGenerator() {
        if (level == null || level.isClientSide()) return false;
        return com.hzldm.createcargodispatch.cargo.StationGroupHelper
                .findBoundGeneratorPos(level, getBlockPos(), stationType, stationId) != null;
    }

    /** 附近是否已连接同组货物检测器（编号/类型匹配且在绑定半径内） */
    public boolean hasNearbyDetector() {
        if (level == null || level.isClientSide()) return false;
        return !com.hzldm.createcargodispatch.cargo.StationGroupHelper
                .findBoundDetectorPositions(level, getBlockPos(), stationType, stationId).isEmpty();
    }

    /**
     * 扫描本站绑定检测器可提交的货箱（提交 Tab 显示用）
     *
     * 原理：
     *  - 通过 StationGroupHelper 只取「同编号 + 同类型 + 绑定半径内」的检测器，
     *    邻居站（哪怕同类型 48 格内）的检测器绝不混入
     *  - 对每个检测器：调用 collectMatchableCargos() 收集其匹配的货箱（SubLevel + 静态，
     *    检测器内部 isRouteMatch 已含「目标站编号严格匹配」）
     *  - 汇总后进行「全局跨检测器去重」：
     *      1. SubLevel 货箱：按 SubLevel UUID 去重
     *      2. 静态货箱：按 controllerPos 去重
     *      3. 订单号+检测器位置 兜底去重键
     *  - 40 tick 缓存，避免客户端 4 秒轮询时服务端重复扫描
     */
    public List<SubmittableCargo> scanNearbySubmittableCargos() {
        if (!(level instanceof ServerLevel serverLevel)) return List.of();
        long nowTick = level.getGameTime();
        if (cachedSubmitList != null && (nowTick - cachedSubmitListTick) < SUBMIT_LIST_CACHE_TICKS) {
            return cachedSubmitList;
        }

        List<SubmittableCargo> raw = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        BlockPos myPos = getBlockPos();

        // 只扫描本站同组绑定检测器（O(检测器数量) 注册表查找，距离+编号双重过滤）
        List<BlockPos> boundDetectors = com.hzldm.createcargodispatch.cargo.StationGroupHelper
                .findBoundDetectorPositions(level, myPos, stationType, stationId);

        // 先把所有绑定 detector 扫描到的原始条目收集到 raw
        for (BlockPos detPos : boundDetectors) {
            if (!(level.isLoaded(detPos))) continue;
            BlockEntity be = level.getBlockEntity(detPos);
            if (!(be instanceof CargoDetectorBlockEntity detector)) continue;

            // detector 直接把匹配条目写入 raw（内部已做 SubLevel UUID / 静态主方块 单 detector 内去重）
            detector.collectMatchableCargos(serverLevel, detPos, raw);
        }

        // 跨 detector 去重：优先 SubLevel UUID → controllerPos → orderId+detectorPos
        List<SubmittableCargo> result = new ArrayList<>(raw.size());
        for (SubmittableCargo c : raw) {
            Object key;
            if (c.subLevelUuid() != null) {
                key = c.subLevelUuid();
            } else if (c.controllerPos() != null) {
                key = c.controllerPos();
            } else {
                key = c.orderId() + "@" + c.detectorPos();
            }
            if (!seen.add(key)) continue;
            result.add(c);
        }

        this.cachedSubmitList = Collections.unmodifiableList(result);
        this.cachedSubmitListTick = nowTick;
        return this.cachedSubmitList;
    }

    // ==================================================================
    // 检测器缓存失效广播
    // ==================================================================

    /**
     * 通知附近检测器清 autoSubmit 缓存
     * 原理：autoSubmit 状态变更时调用，让检测器下一次扫描时重新读取站的 autoSubmit
     *       避免检测器缓存的 cachedAutoSubmit 5 秒内仍然按旧值判断
     */
    public void invalidateNearbyDetectorCaches() {
        if (level == null || level.isClientSide()) return;
        // 只失效本站同组绑定检测器（编号/类型匹配 + 绑定半径），不再广播给邻居站检测器
        for (BlockPos detPos : com.hzldm.createcargodispatch.cargo.StationGroupHelper
                .findBoundDetectorPositions(level, getBlockPos(), stationType, stationId)) {
            if (!level.isLoaded(detPos)) continue;
            BlockEntity be = level.getBlockEntity(detPos);
            if (be instanceof CargoDetectorBlockEntity d) {
                d.invalidateAutoSubmitCache();
            }
        }
    }
}
