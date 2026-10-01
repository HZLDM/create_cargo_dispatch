package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：切换货运站自动提交开关
 *
 * 原理：
 *  - 玩家在提交页面点击开关
 *  - 服务端找到 station，调用 setAutoSubmit()
 *  - station 会在 setAutoSubmit 里 setChanged + 发送更新包
 */
public record ToggleAutoSubmitPayload(int stationX, int stationY, int stationZ,
                                      boolean autoSubmit) implements CustomPacketPayload {

    public static final Type<ToggleAutoSubmitPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "toggle_auto_submit"));

    public static final StreamCodec<FriendlyByteBuf, ToggleAutoSubmitPayload> STREAM_CODEC =
            StreamCodec.of(ToggleAutoSubmitPayload::encode, ToggleAutoSubmitPayload::decode);

    public static void encode(FriendlyByteBuf buf, ToggleAutoSubmitPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
        buf.writeBoolean(payload.autoSubmit());
    }

    public static ToggleAutoSubmitPayload decode(FriendlyByteBuf buf) {
        return new ToggleAutoSubmitPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
