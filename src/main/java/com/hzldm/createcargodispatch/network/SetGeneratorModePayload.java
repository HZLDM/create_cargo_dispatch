package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：设置货物生成器出货模式（地面直接生成 / 连接器检测）
 */
public record SetGeneratorModePayload(int x, int y, int z, String mode)
        implements CustomPacketPayload {

    public static final Type<SetGeneratorModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "set_generator_mode"));

    public static final StreamCodec<FriendlyByteBuf, SetGeneratorModePayload> STREAM_CODEC =
            StreamCodec.of(SetGeneratorModePayload::encode, SetGeneratorModePayload::decode);

    static void encode(FriendlyByteBuf buf, SetGeneratorModePayload payload) {
        buf.writeInt(payload.x());
        buf.writeInt(payload.y());
        buf.writeInt(payload.z());
        buf.writeUtf(payload.mode(), 16);
    }

    static SetGeneratorModePayload decode(FriendlyByteBuf buf) {
        return new SetGeneratorModePayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
