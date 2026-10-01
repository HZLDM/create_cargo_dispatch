package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：请求打开玩家订单页
 *
 * 原理：
 *  - 客户端无法直接打开菜单（openMenu 是服务端方法）
 *  - 客户端发送此 payload 请求服务端打开菜单
 *  - 服务端收到后调用 player.openMenu()
 *  - NeoForge 自动同步到客户端打开 PlayerOrdersScreen
 */
public record OpenOrdersMenuPayload() implements CustomPacketPayload {

    public static final Type<OpenOrdersMenuPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "open_orders_menu"));

    public static final StreamCodec<FriendlyByteBuf, OpenOrdersMenuPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenOrdersMenuPayload());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
