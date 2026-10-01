package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端→客户端：通知客户端删除 Xaero 路径点
 *
 * 原理：
 *  - 订单完成后服务端发送此包
 *  - 客户端在主线程调用 XaeroWaypointService.removeWaypointOnClient
 *  - 通过路径点名称匹配删除（添加时使用 "Cargo #" + orderId 命名）
 */
public record RemoveWaypointPayload(String name) implements CustomPacketPayload {

    public static final Type<RemoveWaypointPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "remove_waypoint"));

    public static final StreamCodec<FriendlyByteBuf, RemoveWaypointPayload> STREAM_CODEC =
            StreamCodec.of(RemoveWaypointPayload::encode, RemoveWaypointPayload::decode);

    public static void encode(FriendlyByteBuf buf, RemoveWaypointPayload payload) {
        buf.writeUtf(payload.name, 128);
    }

    public static RemoveWaypointPayload decode(FriendlyByteBuf buf) {
        return new RemoveWaypointPayload(buf.readUtf(128));
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
