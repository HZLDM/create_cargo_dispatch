package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 服务端→客户端：通知客户端添加 Xaero 路径点
 *
 * 原理：
 *  - 服务端处理接单成功后，向客户端发送此包
 *  - 客户端在主线程调用 XaeroWaypointService.addWaypointOnClient
 *  - Xaero 不存在时客户端静默跳过
 */
public record AddWaypointPayload(int targetX, int targetY, int targetZ,
                                 String dimension, String name, String initials) implements CustomPacketPayload {

    public static final Type<AddWaypointPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "add_waypoint"));

    public static final StreamCodec<FriendlyByteBuf, AddWaypointPayload> STREAM_CODEC =
            StreamCodec.of(AddWaypointPayload::encode, AddWaypointPayload::decode);

    public static void encode(FriendlyByteBuf buf, AddWaypointPayload payload) {
        buf.writeInt(payload.targetX);
        buf.writeInt(payload.targetY);
        buf.writeInt(payload.targetZ);
        buf.writeUtf(payload.dimension);
        buf.writeUtf(payload.name);
        buf.writeUtf(payload.initials);
    }

    public static AddWaypointPayload decode(FriendlyByteBuf buf) {
        return new AddWaypointPayload(
                buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readUtf(256),
                buf.readUtf(128),
                buf.readUtf(16)
        );
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
