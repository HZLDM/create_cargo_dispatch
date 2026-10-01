package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端→服务端：调试页选择目标站（targets 列表索引；-1=清除目标）。编辑对象为主手调试物品 */
public record UpdateDebugCargoTargetPayload(int targetIndex) implements CustomPacketPayload {

    public static final Type<UpdateDebugCargoTargetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "update_debug_cargo_target"));

    public static final StreamCodec<FriendlyByteBuf, UpdateDebugCargoTargetPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeInt(p.targetIndex),
                    buf -> new UpdateDebugCargoTargetPayload(buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
