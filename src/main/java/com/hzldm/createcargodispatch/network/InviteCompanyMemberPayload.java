package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：创建者按玩家名邀请在线玩家 */
public record InviteCompanyMemberPayload(String playerName) implements CustomPacketPayload {

    public static final Type<InviteCompanyMemberPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "invite_company_member"));

    public static final StreamCodec<FriendlyByteBuf, InviteCompanyMemberPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUtf(p.playerName(), 32),
                    buf -> new InviteCompanyMemberPayload(buf.readUtf(32)));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
