package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求断开已连接的站点
 *
 * 原理：
 *  - 玩家在连接页点击「断开」按钮发送
 *  - 携带站点坐标
 *  - 服务端从 LinkageManager 移除该站点并重新同步列表
 */
public record DisconnectStationPayload(int stationX, int stationY, int stationZ) implements CustomPacketPayload {

    public static final Type<DisconnectStationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "disconnect_station"));

    public static final StreamCodec<FriendlyByteBuf, DisconnectStationPayload> STREAM_CODEC =
            StreamCodec.of(DisconnectStationPayload::encode, DisconnectStationPayload::decode);

    public static void encode(FriendlyByteBuf buf, DisconnectStationPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
    }

    public static DisconnectStationPayload decode(FriendlyByteBuf buf) {
        return new DisconnectStationPayload(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
