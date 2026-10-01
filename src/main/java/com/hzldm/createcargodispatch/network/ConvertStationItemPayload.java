package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：把主手「通用货运站/生成器/检测器物品」转换为指定类型的专属物品
 *
 * 原理：
 *  - 玩家在 StationTypeSelectScreen 点击某个类型按钮后发送
 *  - kind 表示通用物品类别（STATION/GENERATOR/DETECTOR），stationTypeId 表示目标货运站类型
 *  - 服务端校验主手物品确实是对应通用物品后，才替换为专属 BlockItem（防手发包乱转）
 */
public record ConvertStationItemPayload(String kind, String stationTypeId) implements CustomPacketPayload {

    public static final Type<ConvertStationItemPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "convert_station_item"));

    public static final StreamCodec<FriendlyByteBuf, ConvertStationItemPayload> STREAM_CODEC =
            StreamCodec.of(ConvertStationItemPayload::encode, ConvertStationItemPayload::decode);

    public static void encode(FriendlyByteBuf buf, ConvertStationItemPayload payload) {
        buf.writeUtf(payload.kind());
        buf.writeUtf(payload.stationTypeId());
    }

    public static ConvertStationItemPayload decode(FriendlyByteBuf buf) {
        return new ConvertStationItemPayload(buf.readUtf(), buf.readUtf());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
