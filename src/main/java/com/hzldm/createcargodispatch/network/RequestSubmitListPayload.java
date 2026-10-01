package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求货运站提交页面的货箱列表
 * 原理：
 *  - 玩家切换到"提交"Tab 时、或每 ~40 tick（2秒）客户端主动请求一次
 *  - 服务端扫描并回复 SyncSubmitListPayload
 */
public record RequestSubmitListPayload(int stationX, int stationY, int stationZ) implements CustomPacketPayload {

    public static final Type<RequestSubmitListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "request_submit_list"));

    public static final StreamCodec<FriendlyByteBuf, RequestSubmitListPayload> STREAM_CODEC =
            StreamCodec.of(RequestSubmitListPayload::encode, RequestSubmitListPayload::decode);

    public static void encode(FriendlyByteBuf buf, RequestSubmitListPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
    }

    public static RequestSubmitListPayload decode(FriendlyByteBuf buf) {
        return new RequestSubmitListPayload(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
