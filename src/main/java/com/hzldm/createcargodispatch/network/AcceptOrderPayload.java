package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：接单请求
 *
 * 原理：
 *  - 玩家在货运站 UI 点击接单时，发送订单 ID 和货运站方块位置
 *  - 服务端通过 stationPos 查找最近的货物生成器并委托生成
 */
public record AcceptOrderPayload(String orderId,
                                 int stationX, int stationY, int stationZ) implements CustomPacketPayload {

    public static final Type<AcceptOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "accept_order"));

    public static final StreamCodec<FriendlyByteBuf, AcceptOrderPayload> STREAM_CODEC =
            StreamCodec.of(AcceptOrderPayload::encode, AcceptOrderPayload::decode);

    public static void encode(FriendlyByteBuf buf, AcceptOrderPayload payload) {
        buf.writeUtf(payload.orderId);
        buf.writeInt(payload.stationX);
        buf.writeInt(payload.stationY);
        buf.writeInt(payload.stationZ);
    }

    public static AcceptOrderPayload decode(FriendlyByteBuf buf) {
        return new AcceptOrderPayload(
                buf.readUtf(64),
                buf.readInt(),
                buf.readInt(),
                buf.readInt()
        );
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
