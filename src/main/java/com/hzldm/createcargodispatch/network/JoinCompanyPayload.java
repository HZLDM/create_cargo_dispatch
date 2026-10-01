package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 客户端→服务端：加入指定联合运输（邀请接受与列表加入共用） */
public record JoinCompanyPayload(UUID companyId) implements CustomPacketPayload {

    public static final Type<JoinCompanyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "join_company"));

    public static final StreamCodec<FriendlyByteBuf, JoinCompanyPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.companyId()),
                    buf -> new JoinCompanyPayload(buf.readUUID()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
