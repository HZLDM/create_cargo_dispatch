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
 * 服务端→客户端：同步玩家正在进行的订单列表
 *
 * 原理：
 *  - 玩家打开订单页时服务端发送该玩家所有活跃订单
 *  - 客户端订单页 UI 渲染订单列表
 *  - 放弃订单后服务端重新发送更新后的列表
 *  - 使用简化字段避免直接序列化 OrderData
 *  - reward 字段语义为「货运币数量」（不再下发实物奖励物品）
 */
public record SyncActiveOrdersPayload(List<ActiveOrderEntry> orders) implements CustomPacketPayload {

    public static final Type<SyncActiveOrdersPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_active_orders"));

    public static final StreamCodec<FriendlyByteBuf, SyncActiveOrdersPayload> STREAM_CODEC =
            StreamCodec.of(SyncActiveOrdersPayload::encode, SyncActiveOrdersPayload::decode);

    /** 单个活跃订单的网络传输数据 */
    public record ActiveOrderEntry(String orderId,
                                   int startX, int startY, int startZ, String startDim,
                                   int targetX, int targetY, int targetZ, String targetDim,
                                   String transportType, String cargoItemId,
                                   int cargoCount, int reward,
                                   String sourceStationType, String targetStationType) {
        public static ActiveOrderEntry from(OrderData order) {
            return new ActiveOrderEntry(
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
                    order.getTargetStationType().getId()
            );
        }
    }

    public static void encode(FriendlyByteBuf buf, SyncActiveOrdersPayload payload) {
        buf.writeInt(payload.orders.size());
        for (ActiveOrderEntry entry : payload.orders) {
            buf.writeUtf(entry.orderId(), 64);
            buf.writeInt(entry.startX());
            buf.writeInt(entry.startY());
            buf.writeInt(entry.startZ());
            buf.writeUtf(entry.startDim(), 256);
            buf.writeInt(entry.targetX());
            buf.writeInt(entry.targetY());
            buf.writeInt(entry.targetZ());
            buf.writeUtf(entry.targetDim(), 256);
            buf.writeUtf(entry.transportType(), 32);
            buf.writeUtf(entry.cargoItemId(), 256);
            buf.writeInt(entry.cargoCount());
            buf.writeInt(entry.reward());
            buf.writeUtf(entry.sourceStationType(), 32);
            buf.writeUtf(entry.targetStationType(), 32);
        }
    }

    public static SyncActiveOrdersPayload decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<ActiveOrderEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new ActiveOrderEntry(
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
                    buf.readUtf(32)
            ));
        }
        return new SyncActiveOrdersPayload(list);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
