package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端→服务端：调试页点「生成货箱」，把主手调试物品变为带配置的可放置货箱方块物品 */
public record ApplyDebugCargoPayload() implements CustomPacketPayload {

    public static final Type<ApplyDebugCargoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "apply_debug_cargo"));

    public static final StreamCodec<FriendlyByteBuf, ApplyDebugCargoPayload> STREAM_CODEC =
            StreamCodec.unit(new ApplyDebugCargoPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
