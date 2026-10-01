package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：一键开启/关闭「该玩家全部已连接站点」的新订单提示开关（与单个站点按钮联动）
 *
 * 原理：
 *  - 已连接站点页底部"一键全部提示 开/关"按钮发送
 *  - 服务端一次性修改该玩家所有 ConnectedStation.notifyEnabled 并推送最新 SyncLinkagesPayload
 *  - 单站按钮和总开关按钮均渲染同一份服务端下发数据 → 天然联动
 */
public record ToggleAllStationsNotifyPayload(boolean enabled) implements CustomPacketPayload {

    public static final Type<ToggleAllStationsNotifyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "toggle_all_stations_notify"));

    public static final StreamCodec<FriendlyByteBuf, ToggleAllStationsNotifyPayload> STREAM_CODEC =
            StreamCodec.of(ToggleAllStationsNotifyPayload::encode, ToggleAllStationsNotifyPayload::decode);

    public static void encode(FriendlyByteBuf buf, ToggleAllStationsNotifyPayload payload) {
        buf.writeBoolean(payload.enabled());
    }

    public static ToggleAllStationsNotifyPayload decode(FriendlyByteBuf buf) {
        return new ToggleAllStationsNotifyPayload(buf.readBoolean());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
