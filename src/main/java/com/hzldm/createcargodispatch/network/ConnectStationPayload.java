package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求连接当前货运站到玩家
 *
 * 原理：
 *  - 玩家在货运站UI点击"连接此站点"按钮发送
 *  - 携带货运站坐标
 *  - 服务端从 BlockEntity 读取 stationType 并记录到 LinkageManager
 */
public record ConnectStationPayload(int stationX, int stationY, int stationZ) implements CustomPacketPayload {

    public static final Type<ConnectStationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "connect_station"));

    public static final StreamCodec<FriendlyByteBuf, ConnectStationPayload> STREAM_CODEC =
            StreamCodec.of(ConnectStationPayload::encode, ConnectStationPayload::decode);

    public static void encode(FriendlyByteBuf buf, ConnectStationPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
    }

    public static ConnectStationPayload decode(FriendlyByteBuf buf) {
        return new ConnectStationPayload(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
