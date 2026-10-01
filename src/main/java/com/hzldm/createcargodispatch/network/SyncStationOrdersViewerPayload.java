package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：返回「背包远程查看站订单」的响应（与 SyncOrdersPayload 解耦，避免污染货运站 Tab 全局 ORDERS 缓存）
 *
 * 回包时机：服务端收到 RequestStationOrdersPayload 且玩家确实连接了该站
 * 客户端处理：写入 ClientCargoCache.stationViewerOrders + 站元信息 → 主线程打开 StationOrdersViewerScreen
 * 只读保证：StationOrdersViewerScreen 不渲染任何接单/提交按钮，所有交互按钮均被移除
 */
public record SyncStationOrdersViewerPayload(int stationX, int stationY, int stationZ,
                                             String stationType,
                                             List<SyncOrdersPayload.OrderEntry> orders,
                                             long nextRefreshGameTime) implements CustomPacketPayload {

    public static final Type<SyncStationOrdersViewerPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_station_orders_viewer"));

    public static final StreamCodec<FriendlyByteBuf, SyncStationOrdersViewerPayload> STREAM_CODEC =
            StreamCodec.of(SyncStationOrdersViewerPayload::encode, SyncStationOrdersViewerPayload::decode);

    private static void encode(FriendlyByteBuf buf, SyncStationOrdersViewerPayload p) {
        buf.writeInt(p.stationX());
        buf.writeInt(p.stationY());
        buf.writeInt(p.stationZ());
        buf.writeUtf(p.stationType() != null ? p.stationType() : "", 128);
        List<SyncOrdersPayload.OrderEntry> list = p.orders() != null ? p.orders() : List.of();
        buf.writeInt(list.size());
        for (SyncOrdersPayload.OrderEntry e : list) SyncOrdersPayload.OrderEntry.encode(buf, e);
        buf.writeLong(p.nextRefreshGameTime());
    }

    private static SyncStationOrdersViewerPayload decode(FriendlyByteBuf buf) {
        int sx = buf.readInt();
        int sy = buf.readInt();
        int sz = buf.readInt();
        String type = buf.readUtf(128);
        int n = buf.readInt();
        List<SyncOrdersPayload.OrderEntry> list = new ArrayList<>(Math.min(n, 512));
        for (int i = 0; i < n; i++) list.add(SyncOrdersPayload.OrderEntry.decode(buf));
        long next = buf.readLong();
        return new SyncStationOrdersViewerPayload(sx, sy, sz, type, list, next);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
