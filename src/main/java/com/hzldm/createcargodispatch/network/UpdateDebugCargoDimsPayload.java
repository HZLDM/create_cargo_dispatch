package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：调试货箱编辑页更新货箱尺寸（编辑主手调试物品）。
 */
public record UpdateDebugCargoDimsPayload(int w, int h, int l) implements CustomPacketPayload {

    public static final Type<UpdateDebugCargoDimsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "update_debug_cargo_dims"));

    public static final StreamCodec<FriendlyByteBuf, UpdateDebugCargoDimsPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.w);
                buf.writeVarInt(p.h);
                buf.writeVarInt(p.l);
            }, buf -> new UpdateDebugCargoDimsPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
