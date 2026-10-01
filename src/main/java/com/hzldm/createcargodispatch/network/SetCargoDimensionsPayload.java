package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：设置货运站「新货箱尺寸」偏好（配置页）。
 * 只影响之后新生成的货箱，已存在货箱不变。
 */
public record SetCargoDimensionsPayload(int sx, int sy, int sz,
                                         int stationX, int stationY, int stationZ)
        implements CustomPacketPayload {

    public static final Type<SetCargoDimensionsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "set_cargo_dimensions"));

    public static final StreamCodec<FriendlyByteBuf, SetCargoDimensionsPayload> STREAM_CODEC =
            StreamCodec.of(SetCargoDimensionsPayload::encode, SetCargoDimensionsPayload::decode);

    private static void encode(FriendlyByteBuf buf, SetCargoDimensionsPayload p) {
        buf.writeVarInt(p.sx);
        buf.writeVarInt(p.sy);
        buf.writeVarInt(p.sz);
        buf.writeBlockPos(new net.minecraft.core.BlockPos(p.stationX, p.stationY, p.stationZ));
    }

    private static SetCargoDimensionsPayload decode(FriendlyByteBuf buf) {
        int sx = buf.readVarInt();
        int sy = buf.readVarInt();
        int sz = buf.readVarInt();
        net.minecraft.core.BlockPos pos = buf.readBlockPos();
        return new SetCargoDimensionsPayload(sx, sy, sz, pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
