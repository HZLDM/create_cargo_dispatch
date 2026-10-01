package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：同步已连接站点列表
 *
 * 原理：
 *  - 玩家打开背包订单页或连接新站点时服务端发送
 *  - 客户端渲染已连接的站点列表
 *  - 每条记录是单个站点信息（位置+类型+是否允许新订单提示）
 */
public record SyncLinkagesPayload(List<LinkageEntry> linkages) implements CustomPacketPayload {

    public static final Type<SyncLinkagesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_linkages"));

    public static final StreamCodec<FriendlyByteBuf, SyncLinkagesPayload> STREAM_CODEC =
            StreamCodec.of(SyncLinkagesPayload::encode, SyncLinkagesPayload::decode);

    /**
     * 单个已连接站点的网络传输数据
     * @param notifyEnabled 需求4：该站是否允许聊天+音效提示。判定规则是按"起始站类型维度"——同一 StationType 下只要至少一条站=true，就给玩家播/发。
     */
    public record LinkageEntry(int sourceX, int sourceY, int sourceZ, String sourceType, boolean notifyEnabled) {
    }

    public static void encode(FriendlyByteBuf buf, SyncLinkagesPayload payload) {
        buf.writeInt(payload.linkages.size());
        for (LinkageEntry entry : payload.linkages) {
            buf.writeInt(entry.sourceX());
            buf.writeInt(entry.sourceY());
            buf.writeInt(entry.sourceZ());
            buf.writeUtf(entry.sourceType(), 32);
            buf.writeBoolean(entry.notifyEnabled());
        }
    }

    public static SyncLinkagesPayload decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<LinkageEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new LinkageEntry(
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(32), buf.readBoolean()));
        }
        return new SyncLinkagesPayload(list);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
