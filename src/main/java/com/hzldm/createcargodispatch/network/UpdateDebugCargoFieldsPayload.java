package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端→服务端：调试页更新字段（编辑主手调试物品）。
 * 非 null 字段更新对应值：orderId 订单号；sourceTypeId 货物源站类型；cargoItemId 自动货物物品。
 */
public record UpdateDebugCargoFieldsPayload(String orderId, String sourceTypeId, String cargoItemId)
        implements CustomPacketPayload {

    public static final Type<UpdateDebugCargoFieldsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "update_debug_cargo_fields"));

    public static final StreamCodec<FriendlyByteBuf, UpdateDebugCargoFieldsPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeBoolean(p.orderId != null);
                if (p.orderId != null) buf.writeUtf(p.orderId, 32);
                buf.writeBoolean(p.sourceTypeId != null);
                if (p.sourceTypeId != null) buf.writeUtf(p.sourceTypeId, 32);
                buf.writeBoolean(p.cargoItemId != null);
                if (p.cargoItemId != null) buf.writeUtf(p.cargoItemId, 64);
            }, buf -> {
                String orderId = buf.readBoolean() ? buf.readUtf(32) : null;
                String sourceTypeId = buf.readBoolean() ? buf.readUtf(32) : null;
                String cargoItemId = buf.readBoolean() ? buf.readUtf(64) : null;
                return new UpdateDebugCargoFieldsPayload(orderId, sourceTypeId, cargoItemId);
            });

    public static UpdateDebugCargoFieldsPayload orderId(String orderId) {
        return new UpdateDebugCargoFieldsPayload(orderId, null, null);
    }

    public static UpdateDebugCargoFieldsPayload selection(String sourceTypeId, String cargoItemId) {
        return new UpdateDebugCargoFieldsPayload(null, sourceTypeId, cargoItemId);
    }

    public static UpdateDebugCargoFieldsPayload cargoItem(String cargoItemId) {
        return new UpdateDebugCargoFieldsPayload(null, null, cargoItemId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
