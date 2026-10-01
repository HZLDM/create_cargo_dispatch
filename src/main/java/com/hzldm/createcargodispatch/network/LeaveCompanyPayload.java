package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：退出联合运输（创建者退出=解散） */
public record LeaveCompanyPayload() implements CustomPacketPayload {

    public static final Type<LeaveCompanyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "leave_company"));

    public static final StreamCodec<FriendlyByteBuf, LeaveCompanyPayload> STREAM_CODEC =
            StreamCodec.unit(new LeaveCompanyPayload());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
