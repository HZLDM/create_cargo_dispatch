package com.hzldm.createcargodispatch.menu;

import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.network.AcceptOrderPayload;
import com.hzldm.createcargodispatch.network.RequestSubmitListPayload;
import com.hzldm.createcargodispatch.network.SubmitCargoPayload;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncSubmitListPayload;
import com.hzldm.createcargodispatch.network.ToggleAutoSubmitPayload;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 货运站 UI 菜单（支持「订单 Tab」「提交 Tab」切换）
 *
 * 原理：
 *  - 绑定 CargoStationBlockEntity（订单 Tab 接单、提交 Tab 控制自动提交开关）
 *  - Tab 切换只在客户端 menu 实例中保存当前选中页，Screen 根据 currentTab 渲染不同内容
 *  - 订单 Tab：展示 ClientCargoCache 的订单列表 + 接单按钮
 *  - 提交 Tab：展示 ClientCargoCache 的 SUBMIT_ENTRIES + 手动提交按钮 + 自动提交开关
 *  - 所有提交页面的动作（刷新列表/手动提交/切换开关）统一提供 helper 方法发送对应 Payload
 */
public class CargoGeneratorMenu extends AbstractContainerMenu {

    public enum Tab { ORDERS, SUBMIT, CONFIG }

    private final CargoStationBlockEntity station;
    /** 当前显示的订单列表（仅服务端实例在构造时缓存，客户端每次从 ClientCargoCache 读） */
    private List<SyncOrdersPayload.OrderEntry> orders;
    /** 当前选中的 Tab（客户端） */
    private Tab currentTab = Tab.ORDERS;

    /** 服务端构造：通过 BlockEntity */
    public CargoGeneratorMenu(int containerId, Inventory playerInventory, CargoStationBlockEntity station) {
        super(ModMenuTypes.CARGO_GENERATOR.get(), containerId);
        this.station = station;
        if (playerInventory.player instanceof ServerPlayer serverPlayer) {
            StationType type = station != null ? station.getStationType() : StationType.GENERIC;
            BlockPos stationPos = station != null ? station.getBlockPos() : null;
            // 修复Bug：原来用 getPendingOrders(type) 把「世界上所有同类型不同站的订单」全塞进当前货运站GUI，
            // 导致玩家在A站(牧场)GUI里点击接单，但订单实际属于B站(牧场)，订单startPos是B站的detector位置，
            // verifyStation 在B站detector位置 getBlockEntity 时可能失败 → 报「起始站没有货运站方块已被破坏」
            // （玩家还会觉得我明明站在A站面前没拆任何东西！）
            // 改成 getPendingOrdersForStation(type, stationPos) 精确按「同站」过滤，
            // 与背包「已连接站点→订单按钮」显示口径完全一致，不再错塞别的站订单。
            // 联合运输改造：再叠加「观看者属主」过滤（个人订单仅属主可见、公司订单仅成员可见）
            this.orders = com.hzldm.createcargodispatch.cargo.OrderManager.getPendingOrdersForStation(
                            serverPlayer.serverLevel(), serverPlayer.getUUID(), type, stationPos).stream()
                    .map(SyncOrdersPayload.OrderEntry::from)
                    .toList();
            long nextRefresh = com.hzldm.createcargodispatch.cargo.OrderManager.getLastRefreshTime()
                    + com.hzldm.createcargodispatch.cargo.OrderManager.getNextRefreshInterval();
            PacketDistributor.sendToPlayer(serverPlayer, new SyncOrdersPayload(orders, nextRefresh));

            // 打开 UI 立即同步一次提交列表 + 自动提交开关，不再等客户端 2s 轮询
            if (station != null) {
                com.hzldm.createcargodispatch.network.ModPayloads.sendSubmitList(serverPlayer, station.getBlockPos());
            }

            // 同步玩家已连接站点列表：订单页空提示要据此区分「尚未建立联络线」与「暂无新订单」
            List<com.hzldm.createcargodispatch.cargo.LinkageManager.ConnectedStation> linked =
                    com.hzldm.createcargodispatch.cargo.LinkageManager.get(serverPlayer.serverLevel())
                            .getConnectedStations(serverPlayer.getUUID());
            List<com.hzldm.createcargodispatch.network.SyncLinkagesPayload.LinkageEntry> linkageEntries =
                    linked.stream()
                            .map(s -> new com.hzldm.createcargodispatch.network.SyncLinkagesPayload.LinkageEntry(
                                    s.pos().getX(), s.pos().getY(), s.pos().getZ(),
                                    s.type().getId(), s.notifyEnabled()))
                            .toList();
            PacketDistributor.sendToPlayer(serverPlayer,
                    new com.hzldm.createcargodispatch.network.SyncLinkagesPayload(linkageEntries));
        } else {
            this.orders = null;
        }
    }

    /** 客户端构造：通过 BlockPos 查找 BlockEntity */
    public CargoGeneratorMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenuTypes.CARGO_GENERATOR.get(), containerId);
        if (pos != null) {
            BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
            this.station = be instanceof CargoStationBlockEntity s ? s : null;
        } else {
            this.station = null;
        }
        this.orders = null;
    }

    // ------------------------------------------------------------------
    // Tab 切换（仅 Screen 调用）
    // ------------------------------------------------------------------

    public Tab getCurrentTab() { return currentTab; }

    /**
     * 切换 Tab：切到 SUBMIT 时立即请求一次服务端刷新提交列表
     */
    public void switchTab(Tab tab) {
        if (this.currentTab == tab) return;
        this.currentTab = tab;
        if (tab == Tab.SUBMIT && station != null && station.getLevel() != null && station.getLevel().isClientSide()) {
            requestRefreshSubmitList();
        }
    }

    // ------------------------------------------------------------------
    // 订单页
    // ------------------------------------------------------------------

    public List<SyncOrdersPayload.OrderEntry> getOrders() {
        // —— 修复「其他站点订单突然显示、重开页面消失」的偶发BUG：
        // 旧逻辑：if (orders != null) return orders else return ClientCargoCache.getOrders()（全局缓存）
        //   客户端构造时 orders 字段永远为 null，直接回退到 ClientCargoCache.getOrders() —— 这是「背包远程查看页」
        //   用的**全局未过滤缓存**（只要订单一端是玩家已连接的站就会收进来，包括：矿山→牧场 的起点=矿山，但玩家也连了牧场，
        //   缓存里会有这条），结果当前在「牧场站GUI」打开时，居然显示矿山站发的单！
        //   重新打开页面时，服务端构造时同步的 SyncOrdersPayload 刚好到了 orders 字段或者全局缓存被服务端过滤版覆盖 → 看似消失。
        //
        // 新逻辑：**永远按「当前货运站作为起点」精确过滤**，绝不信任「未按站过滤的全局缓存」：
        //   A) 服务端（isClientSide=false）：直接从 OrderManager 取最新（避免 orders 字段是开屏瞬间的快照，订单变化后GUI不刷新）
        //   B) 客户端（isClientSide=true）：从 ClientCargoCache 全局缓存按 stationPos+stationType 实时过滤，
        //      跟背包 StationOrdersViewerScreen / 服务端 getPendingOrdersForStation 口径100%一致。
        if (station == null) return List.of();
        BlockPos p = station.getBlockPos();
        StationType t = station.getStationType();
        if (p == null) return List.of();
        net.minecraft.world.level.Level level = station.getLevel();

        if (level != null && !level.isClientSide) {
            // 服务端：直接走核心过滤，实时最准
            return com.hzldm.createcargodispatch.cargo.OrderManager
                    .getPendingOrdersForStation(t, p).stream()
                    .map(SyncOrdersPayload.OrderEntry::from)
                    .toList();
        }
        // 客户端：按站坐标+类型实时从全局缓存过滤（带类型参数，避免 viewerStationType 是空导致类型判断跳过）
        String typeId = (t != null) ? t.getId() : "";
        return getClientOrdersForStation(p, typeId);
    }

    /** 客户端：从 ClientCargoCache 按站过滤订单。@OnlyIn 确保服务端不加载 ClientCargoCache。 */
    @OnlyIn(Dist.CLIENT)
    private static List<SyncOrdersPayload.OrderEntry> getClientOrdersForStation(BlockPos p, String typeId) {
        return com.hzldm.createcargodispatch.client.ClientCargoCache.getOrdersForStation(p, typeId);
    }

    public CargoStationBlockEntity getStation() { return station; }

    /** 客户端点击订单：发送接单请求 */
    public void acceptOrder(int orderIndex) {
        List<SyncOrdersPayload.OrderEntry> list = getOrders();
        if (orderIndex < 0 || orderIndex >= list.size()) return;
        SyncOrdersPayload.OrderEntry entry = list.get(orderIndex);
        if (station != null) {
            BlockPos pos = station.getBlockPos();
            PacketDistributor.sendToServer(new AcceptOrderPayload(entry.orderId(), pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    // ------------------------------------------------------------------
    // 提交页辅助方法（由 Screen 调用，统一发 Payload）
    // ------------------------------------------------------------------

    /** 客户端：请求服务端刷新当前站的待提交货箱列表 */
    public void requestRefreshSubmitList() {
        if (station == null) return;
        BlockPos p = station.getBlockPos();
        PacketDistributor.sendToServer(new RequestSubmitListPayload(p.getX(), p.getY(), p.getZ()));
    }

    /** 客户端：手动提交某条货箱 */
    public void submitCargo(SyncSubmitListPayload.SubmitEntry entry) {
        if (station == null || entry == null) return;
        BlockPos sp = station.getBlockPos();
        UUID uuid = entry.hasSubLevel() ? entry.subLevelUuid() : null;
        PacketDistributor.sendToServer(new SubmitCargoPayload(
                sp.getX(), sp.getY(), sp.getZ(),
                entry.detectorX(), entry.detectorY(), entry.detectorZ(),
                entry.hasSubLevel(), uuid,
                entry.controllerX(), entry.controllerY(), entry.controllerZ()));
    }

    /** 客户端：切换自动提交开关 */
    public void toggleAutoSubmit(boolean on) {
        if (station == null) return;
        BlockPos p = station.getBlockPos();
        PacketDistributor.sendToServer(new ToggleAutoSubmitPayload(p.getX(), p.getY(), p.getZ(), on));
    }

    /** 客户端：设置新货箱尺寸偏好（配置页） */
    public void setCargoDimensions(com.hzldm.createcargodispatch.cargo.CargoDimensions dims) {
        if (station == null || dims == null) return;
        BlockPos p = station.getBlockPos();
        PacketDistributor.sendToServer(
                new com.hzldm.createcargodispatch.network.SetCargoDimensionsPayload(
                        dims.width(), dims.height(), dims.length(),
                        p.getX(), p.getY(), p.getZ()));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide()) return true;
        return station != null && !station.isRemoved();
    }
}
