package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：请求打开联合运输页 */
public record OpenCompanyMenuPayload() implements CustomPacketPayload {

    public static final Type<OpenCompanyMenuPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "open_company_menu"));

    public static final StreamCodec<FriendlyByteBuf, OpenCompanyMenuPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenCompanyMenuPayload());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
