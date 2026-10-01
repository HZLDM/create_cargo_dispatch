package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端→客户端：同步联合运输状态
 *
 * 原理：
 *  - inCompany=true：members 为所在公司成员列表，joinables 为空；
 *    同时携带公司经济三要素 balance（货运币）/reputation（声望）/companyLevel（公司等级）
 *  - inCompany=false：members 为空，joinables 为全服可加入公司列表（开放加入制），经济字段补 0
 *  - 任何公司状态变更后服务端向受影响玩家重发整包，客户端整表替换，保证最终一致
 */
public record SyncCompanyPayload(boolean inCompany,
                                 UUID companyId,
                                 String companyName,
                                 UUID creatorId,
                                 List<MemberEntry> members,
                                 List<JoinableEntry> joinables,
                                 long balance,
                                 int reputation,
                                 int companyLevel,
                                 int linkSlots,
                                 long nextUpgradeCost) implements CustomPacketPayload {

    /** nextUpgradeCost 取该值表示公司已达最高等级、不可再升 */
    public static final long COST_MAX_LEVEL = -1L;

    public static final Type<SyncCompanyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_company"));

    public static final StreamCodec<FriendlyByteBuf, SyncCompanyPayload> STREAM_CODEC =
            StreamCodec.of(SyncCompanyPayload::encode, SyncCompanyPayload::decode);

    /** 公司成员条目 */
    public record MemberEntry(UUID id, String name, boolean creator) {
    }

    /** 可加入公司条目 */
    public record JoinableEntry(UUID id, String name, String creatorName, int memberCount) {
    }

    private static void encode(FriendlyByteBuf buf, SyncCompanyPayload p) {
        buf.writeBoolean(p.inCompany);
        if (p.inCompany) {
            buf.writeUUID(p.companyId);
            buf.writeUtf(p.companyName, 48);
            buf.writeUUID(p.creatorId);
            buf.writeInt(p.members.size());
            for (MemberEntry m : p.members) {
                buf.writeUUID(m.id());
                buf.writeUtf(m.name(), 32);
                buf.writeBoolean(m.creator());
            }
            // 经济三要素 + 服务端派生量（槽位/下一级费用，避免客户端读本地 SERVER 配置产生联机不一致）
            buf.writeLong(p.balance);
            buf.writeInt(p.reputation);
            buf.writeInt(p.companyLevel);
            buf.writeInt(p.linkSlots);
            buf.writeLong(p.nextUpgradeCost);
        } else {
            buf.writeInt(p.joinables.size());
            for (JoinableEntry j : p.joinables) {
                buf.writeUUID(j.id());
                buf.writeUtf(j.name(), 48);
                buf.writeUtf(j.creatorName(), 32);
                buf.writeInt(j.memberCount());
            }
        }
    }

    private static SyncCompanyPayload decode(FriendlyByteBuf buf) {
        boolean inCompany = buf.readBoolean();
        if (inCompany) {
            UUID companyId = buf.readUUID();
            String name = buf.readUtf(48);
            UUID creatorId = buf.readUUID();
            int count = buf.readInt();
            List<MemberEntry> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(new MemberEntry(buf.readUUID(), buf.readUtf(32), buf.readBoolean()));
            }
            long balance = buf.readLong();
            int reputation = buf.readInt();
            int companyLevel = buf.readInt();
            int linkSlots = buf.readInt();
            long nextUpgradeCost = buf.readLong();
            return new SyncCompanyPayload(true, companyId, name, creatorId, members, List.of(),
                    balance, reputation, companyLevel, linkSlots, nextUpgradeCost);
        }
        int count = buf.readInt();
        List<JoinableEntry> joinables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            joinables.add(new JoinableEntry(buf.readUUID(), buf.readUtf(48), buf.readUtf(32), buf.readInt()));
        }
        return new SyncCompanyPayload(false, null, "", null, List.of(), joinables, 0L, 0, 1, 0, COST_MAX_LEVEL);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
