package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：放弃订单请求
 *
 * 原理：
 *  - 玩家在订单页点击"放弃"按钮时发送
 *  - 服务端处理：删除货箱方块 → 取消订单 → 删除路径点 → 重新同步活跃订单
 *  - 使用 orderId 字符串标识订单，避免序列化完整 OrderData
 */
public record AbandonOrderPayload(String orderId) implements CustomPacketPayload {

    public static final Type<AbandonOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "abandon_order"));

    public static final StreamCodec<FriendlyByteBuf, AbandonOrderPayload> STREAM_CODEC =
            StreamCodec.of(AbandonOrderPayload::encode, AbandonOrderPayload::decode);

    public static void encode(FriendlyByteBuf buf, AbandonOrderPayload payload) {
        buf.writeUtf(payload.orderId(), 64);
    }

    public static AbandonOrderPayload decode(FriendlyByteBuf buf) {
        return new AbandonOrderPayload(buf.readUtf(64));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
