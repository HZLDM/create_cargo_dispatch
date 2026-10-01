package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端→客户端：同步货运站提交页面的「可提交货箱列表」
 *
 * 原理：
 *  - 玩家切换到「提交」页面时，服务端扫描所有匹配检测器附近的货箱，发送列表给客户端
 *  - 客户端渲染每个货箱条目（物品汇总 + 「提交」按钮）
 *  - 条目格式：SubLevel 用 UUID 标识、静态货箱用 controllerPos 标识
 */
public record SyncSubmitListPayload(int stationX, int stationY, int stationZ,
                                    List<SubmitEntry> entries,
                                    boolean autoSubmit,
                                    boolean hasGenerator,
                                    boolean hasDetector) implements CustomPacketPayload {

    public static final Type<SyncSubmitListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_submit_list"));

    public static final StreamCodec<FriendlyByteBuf, SyncSubmitListPayload> STREAM_CODEC =
            StreamCodec.of(SyncSubmitListPayload::encode, SyncSubmitListPayload::decode);

    /**
     * 单个待提交货箱的网络传输数据
     *
     * @param index             服务端列表索引，客户端点击提交按钮时回传
     * @param hasSubLevel       true=SubLevel 货箱, false=静态货箱
     * @param subLevelUuid      SubLevel UUID（静态货箱为 null）
     * @param controllerX       静态货箱 controllerPos（SubLevel 时无效）
     * @param controllerY       同上
     * @param controllerZ       同上
     * @param detectorX         检测到该货箱的检测器位置（显示用）
     * @param detectorY         同上
     * @param detectorZ         同上
     * @param totalItemCount    合并后物品总数
     * @param orderId           所属订单 ID
     * @param sourceStationType 源站点类型 ID
     * @param items             显示用物品列表（合并后）：每一项是 [itemId, count]
     */
    public record SubmitEntry(int index,
                              boolean hasSubLevel,
                              @org.jetbrains.annotations.Nullable UUID subLevelUuid,
                              int controllerX, int controllerY, int controllerZ,
                              int detectorX, int detectorY, int detectorZ,
                              int totalItemCount,
                              String orderId,
                              String sourceStationType,
                              List<ItemSummary> items) {}

    public record ItemSummary(String itemId, int count) {}

    private static void encodeItem(FriendlyByteBuf buf, ItemSummary it) {
        buf.writeUtf(it.itemId(), 256);
        buf.writeInt(it.count());
    }

    private static ItemSummary decodeItem(FriendlyByteBuf buf) {
        return new ItemSummary(buf.readUtf(256), buf.readInt());
    }

    public static void encode(FriendlyByteBuf buf, SyncSubmitListPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
        buf.writeBoolean(payload.autoSubmit());
        buf.writeBoolean(payload.hasGenerator());
        buf.writeBoolean(payload.hasDetector());
        buf.writeInt(payload.entries().size());
        for (SubmitEntry e : payload.entries()) {
            buf.writeInt(e.index());
            buf.writeBoolean(e.hasSubLevel());
            if (e.hasSubLevel()) {
                buf.writeUUID(e.subLevelUuid());
            } else {
                buf.writeInt(e.controllerX());
                buf.writeInt(e.controllerY());
                buf.writeInt(e.controllerZ());
            }
            buf.writeInt(e.detectorX());
            buf.writeInt(e.detectorY());
            buf.writeInt(e.detectorZ());
            buf.writeInt(e.totalItemCount());
            buf.writeUtf(e.orderId(), 64);
            buf.writeUtf(e.sourceStationType(), 32);
            buf.writeInt(e.items().size());
            for (ItemSummary it : e.items()) encodeItem(buf, it);
        }
    }

    public static SyncSubmitListPayload decode(FriendlyByteBuf buf) {
        int sx = buf.readInt();
        int sy = buf.readInt();
        int sz = buf.readInt();
        boolean auto = buf.readBoolean();
        boolean hasGenerator = buf.readBoolean();
        boolean hasDetector = buf.readBoolean();
        int count = buf.readInt();
        List<SubmitEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = buf.readInt();
            boolean hasSub = buf.readBoolean();
            UUID subUuid = null;
            int cx = 0, cy = 0, cz = 0;
            if (hasSub) {
                subUuid = buf.readUUID();
            } else {
                cx = buf.readInt();
                cy = buf.readInt();
                cz = buf.readInt();
            }
            int dx = buf.readInt();
            int dy = buf.readInt();
            int dz = buf.readInt();
            int total = buf.readInt();
            String orderId = buf.readUtf(64);
            String srcType = buf.readUtf(32);
            int itemCount = buf.readInt();
            List<ItemSummary> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) items.add(decodeItem(buf));
            entries.add(new SubmitEntry(idx, hasSub, subUuid, cx, cy, cz, dx, dy, dz, total, orderId, srcType, items));
        }
        return new SyncSubmitListPayload(sx, sy, sz, entries, auto, hasGenerator, hasDetector);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
