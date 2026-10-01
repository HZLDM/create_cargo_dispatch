package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 客户端→服务端：创建者将成员移出联合运输 */
public record KickCompanyMemberPayload(UUID targetId) implements CustomPacketPayload {

    public static final Type<KickCompanyMemberPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "kick_company_member"));

    public static final StreamCodec<FriendlyByteBuf, KickCompanyMemberPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.targetId()),
                    buf -> new KickCompanyMemberPayload(buf.readUUID()));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
