package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.network.AddWaypointPayload;
import com.hzldm.createcargodispatch.network.RemoveWaypointPayload;
import com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import com.hzldm.createcargodispatch.network.SyncDebugCargoPayload;
import com.hzldm.createcargodispatch.network.SyncLinkagesPayload;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncStationOrdersViewerPayload;
import com.hzldm.createcargodispatch.network.SyncSubmitListPayload;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端 S2C payload 处理器（仅客户端加载）。
 *
 * <p>为什么独立成类：
 * <ul>
 *   <li>这些处理器引用 ClientCargoCache / Minecraft / Screen 等客户端类，
 *       若放在公共 ModPayloads 中，服务端加载时 RuntimeDistCleaner 会因
 *       常量池含客户端类引用而抛出 "Attempted to load class ... for invalid dist DEDICATED_SERVER"。</li>
 *   <li>整个类标记 @OnlyIn(Dist.CLIENT)，服务端直接剥离，绝不参与类加载。</li>
 *   <li>注册入口 registerClient() 由 ModPayloads.register() 在 Dist.CLIENT 分支调用。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-NetClient");

    private ClientPayloadHandlers() {}

    /** 注册所有 S2C 处理器（仅客户端调用） */
    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(SyncOrdersPayload.TYPE, SyncOrdersPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncOrders)
                .playToClient(SyncActiveOrdersPayload.TYPE, SyncActiveOrdersPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncActiveOrders)
                .playToClient(SyncLinkagesPayload.TYPE, SyncLinkagesPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncLinkages)
                .playToClient(AddWaypointPayload.TYPE, AddWaypointPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleAddWaypoint)
                .playToClient(RemoveWaypointPayload.TYPE, RemoveWaypointPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleRemoveWaypoint)
                .playToClient(SyncSubmitListPayload.TYPE, SyncSubmitListPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncSubmitList)
                .playToClient(SyncStationOrdersViewerPayload.TYPE, SyncStationOrdersViewerPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncStationOrdersViewer)
                .playToClient(SyncDebugCargoPayload.TYPE, SyncDebugCargoPayload.STREAM_CODEC,
                        ClientPayloadHandlers::handleSyncDebugCargo);
    }

    private static void handleSyncOrders(SyncOrdersPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.updateOrders(payload.orders(), payload.nextRefreshGameTime())
        ).exceptionally(ex -> {
            LOGGER.error("处理订单同步失败", ex);
            return null;
        });
    }

    private static void handleAddWaypoint(AddWaypointPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.addWaypoint(
                        payload.targetX(), payload.targetY(), payload.targetZ(),
                        payload.dimension(), payload.name(), payload.initials())
        ).exceptionally(ex -> {
            LOGGER.error("处理路径点添加失败", ex);
            return null;
        });
    }

    private static void handleRemoveWaypoint(RemoveWaypointPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.removeWaypoint(payload.name())
        ).exceptionally(ex -> {
            LOGGER.error("处理路径点删除失败", ex);
            return null;
        });
    }

    private static void handleSyncActiveOrders(SyncActiveOrdersPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.updateActiveOrders(payload.orders())
        ).exceptionally(ex -> {
            LOGGER.error("处理活跃订单同步失败", ex);
            return null;
        });
    }

    private static void handleSyncLinkages(SyncLinkagesPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.updateLinkages(payload.linkages())
        ).exceptionally(ex -> {
            LOGGER.error("处理联络线同步失败", ex);
            return null;
        });
    }

    private static void handleSyncSubmitList(SyncSubmitListPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientCargoCache.updateSubmitList(
                        payload.stationX(), payload.stationY(), payload.stationZ(),
                        payload.entries(), payload.autoSubmit(),
                        payload.hasGenerator(), payload.hasDetector())
        ).exceptionally(ex -> {
            LOGGER.error("处理提交列表同步失败", ex);
            return null;
        });
    }

    /**
     * 处理远程查看站订单回包：写缓存 + 主线程打开 StationOrdersViewerScreen。
     * 已打开时不重建 Screen（避免 onClose 清缓存导致无限重发包）。
     */
    private static void handleSyncStationOrdersViewer(SyncStationOrdersViewerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientCargoCache.updateStationViewerOrders(
                    payload.stationX(), payload.stationY(), payload.stationZ(),
                    payload.stationType(), payload.orders(), payload.nextRefreshGameTime());

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (mc.screen instanceof StationOrdersViewerScreen) {
                        return;
                    }
                    mc.setScreen(new StationOrdersViewerScreen());
                } catch (Throwable t) {
                    LOGGER.error("打开 StationOrdersViewerScreen 失败", t);
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.translatable("create_cargo_dispatch.station_viewer.open_failed"),
                                false);
                    }
                }
            });
        }).exceptionally(ex -> {
            LOGGER.error("处理同步站订单查看数据失败", ex);
            return null;
        });
    }

    private static void handleSyncDebugCargo(SyncDebugCargoPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientDebugCargoCache.update(payload));
    }

    // ===================== 联合运输公司 S2C =====================

    /** 注册联合运输公司的 S2C 处理器（由 CompanyPayloadHandlers 在客户端分支调用） */
    public static void registerCompanyClient(PayloadRegistrar registrar) {
        registrar.playToClient(SyncCompanyPayload.TYPE, SyncCompanyPayload.STREAM_CODEC,
                ClientPayloadHandlers::handleSyncCompany);
    }

    /** 客户端：整表替换联合运输状态 */
    private static void handleSyncCompany(SyncCompanyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientCompanyCache.update(payload))
                .exceptionally(ex -> {
                    LOGGER.error("同步联合运输状态失败", ex);
                    return null;
                });
    }
}
