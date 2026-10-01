package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求查看「背包已连接的某货运站」的未接订单
 *
 * 触发：ConnectedStationsScreen 每行的『📋 订单』按钮
 * 校验：服务端 Handler 必须校验「玩家是否真的连接了该站（用 LinkageManager.getConnectedPositionsPacked 查 sourceX/Y/Z 命中）」——未连接直接不回包，防止恶意玩家遍历坐标偷窥所有订单
 * 筛选：仅返回 PENDING 订单中 sourceStationType == 站类型 且「玩家可见性过滤」通过的那些订单（复用 buildFilteredPendingOrderEntries 逻辑）
 * 回包：SyncStationOrdersViewerPayload
 */
public record RequestStationOrdersPayload(int sourceX, int sourceY, int sourceZ,
                                          String sourceType) implements CustomPacketPayload {

    public static final Type<RequestStationOrdersPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "request_station_orders"));

    public static final StreamCodec<FriendlyByteBuf, RequestStationOrdersPayload> STREAM_CODEC =
            StreamCodec.of(RequestStationOrdersPayload::encode, RequestStationOrdersPayload::decode);

    private static void encode(FriendlyByteBuf buf, RequestStationOrdersPayload payload) {
        buf.writeInt(payload.sourceX);
        buf.writeInt(payload.sourceY);
        buf.writeInt(payload.sourceZ);
        buf.writeUtf(payload.sourceType != null ? payload.sourceType : "", 128);
    }

    private static RequestStationOrdersPayload decode(FriendlyByteBuf buf) {
        int sx = buf.readInt();
        int sy = buf.readInt();
        int sz = buf.readInt();
        String type = buf.readUtf(128);
        return new RequestStationOrdersPayload(sx, sy, sz, type);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
