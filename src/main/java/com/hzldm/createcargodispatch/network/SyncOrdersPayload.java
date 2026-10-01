package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.cargo.OrderData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：同步订单列表 + 下次刷新时间
 *
 * 原理：
 *  - 玩家打开货物生成器 UI 时，服务端发送当前可接单订单列表
 *  - 客户端 UI 渲染订单列表 + 刷新倒计时
 *  - nextRefreshGameTime 为绝对游戏时间，客户端通过 level.getGameTime() 实时计算剩余
 *  - 使用简化字段避免直接序列化 OrderData
 *  - reward 字段语义为「货运币数量」，完成后入接单玩家所属公司账户（不再下发实物奖励物品）
 */
public record SyncOrdersPayload(List<OrderEntry> orders, long nextRefreshGameTime) implements CustomPacketPayload {

    public static final Type<SyncOrdersPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_orders"));

    public static final StreamCodec<FriendlyByteBuf, SyncOrdersPayload> STREAM_CODEC =
            StreamCodec.of(SyncOrdersPayload::encode, SyncOrdersPayload::decode);

    /** 单个订单的网络传输数据 */
    public record OrderEntry(String orderId,
                             int startX, int startY, int startZ, String startDim,
                             int targetX, int targetY, int targetZ, String targetDim,
                             String transportType, String cargoItemId,
                             int cargoCount, int reward,
                             String sourceStationType, String targetStationType,
                             long expireAtGameTime) {
        public static OrderEntry from(OrderData order) {
            return new OrderEntry(
                    order.getOrderId(),
                    order.getStartPos().getX(), order.getStartPos().getY(), order.getStartPos().getZ(),
                    order.getStartDimension() != null ? order.getStartDimension().toString() : "",
                    order.getTargetPos().getX(), order.getTargetPos().getY(), order.getTargetPos().getZ(),
                    order.getTargetDimension() != null ? order.getTargetDimension().toString() : "",
                    order.getTransportType().getId(),
                    order.getCargoItemId() != null ? order.getCargoItemId() : "",
                    order.getCargoCount(),
                    order.getReward(),
                    order.getStationType().getId(),
                    order.getTargetStationType().getId(),
                    order.getExpireAtGameTime()
            );
        }

        /** 编码单个 OrderEntry（供 SyncStationOrdersViewerPayload 等外部复用，避免内联重复） */
        public static void encode(FriendlyByteBuf buf, OrderEntry e) {
            buf.writeUtf(e.orderId(), 64);
            buf.writeInt(e.startX());
            buf.writeInt(e.startY());
            buf.writeInt(e.startZ());
            buf.writeUtf(e.startDim(), 256);
            buf.writeInt(e.targetX());
            buf.writeInt(e.targetY());
            buf.writeInt(e.targetZ());
            buf.writeUtf(e.targetDim(), 256);
            buf.writeUtf(e.transportType(), 32);
            buf.writeUtf(e.cargoItemId(), 256);
            buf.writeInt(e.cargoCount());
            buf.writeInt(e.reward());
            buf.writeUtf(e.sourceStationType(), 32);
            buf.writeUtf(e.targetStationType(), 32);
            buf.writeLong(e.expireAtGameTime());
        }

        /** 解码单个 OrderEntry（与 encode 字段顺序严格一致） */
        public static OrderEntry decode(FriendlyByteBuf buf) {
            return new OrderEntry(
                    buf.readUtf(64),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(256),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(256),
                    buf.readUtf(32),
                    buf.readUtf(256),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(32),
                    buf.readUtf(32),
                    buf.readLong()
            );
        }
    }

    public static void encode(FriendlyByteBuf buf, SyncOrdersPayload payload) {
        buf.writeLong(payload.nextRefreshGameTime);
        buf.writeInt(payload.orders.size());
        for (OrderEntry entry : payload.orders) {
            OrderEntry.encode(buf, entry);
        }
    }

    public static SyncOrdersPayload decode(FriendlyByteBuf buf) {
        long nextRefresh = buf.readLong();
        int count = buf.readInt();
        List<OrderEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(OrderEntry.decode(buf));
        }
        return new SyncOrdersPayload(list, nextRefresh);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
