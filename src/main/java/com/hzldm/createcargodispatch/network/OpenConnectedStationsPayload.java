package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求打开已连接站点页
 *
 * 原理：
 *  - 客户端标签点击时发送
 *  - 服务端调用 player.openMenu() 打开 ConnectedStationsMenu
 *  - NeoForge 自动同步到客户端打开 ConnectedStationsScreen
 */
public record OpenConnectedStationsPayload() implements CustomPacketPayload {

    public static final Type<OpenConnectedStationsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "open_connected_stations"));

    public static final StreamCodec<FriendlyByteBuf, OpenConnectedStationsPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenConnectedStationsPayload());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
