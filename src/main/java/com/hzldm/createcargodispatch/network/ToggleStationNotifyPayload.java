package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：切换单个已连接站点的「新订单提示开关」（聊天栏+音效一并控制）
 *
 * 原理：
 *  - 玩家在已连接站点页点击 🔔/🔕 按钮发送
 *  - 服务端调用 LinkageManager.setStationNotifyEnabled 修改并持久化
 *  - 修改后服务端自动 pushLinkagesToPlayer，客户端渲染与服务端状态保持联动
 */
public record ToggleStationNotifyPayload(int stationX, int stationY, int stationZ, boolean enabled) implements CustomPacketPayload {

    public static final Type<ToggleStationNotifyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "toggle_station_notify"));

    public static final StreamCodec<FriendlyByteBuf, ToggleStationNotifyPayload> STREAM_CODEC =
            StreamCodec.of(ToggleStationNotifyPayload::encode, ToggleStationNotifyPayload::decode);

    public static void encode(FriendlyByteBuf buf, ToggleStationNotifyPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
        buf.writeBoolean(payload.enabled());
    }

    public static ToggleStationNotifyPayload decode(FriendlyByteBuf buf) {
        return new ToggleStationNotifyPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean());
    }

    /** 快捷构造（从 BlockPos） */
    public static ToggleStationNotifyPayload of(BlockPos pos, boolean enabled) {
        return new ToggleStationNotifyPayload(pos.getX(), pos.getY(), pos.getZ(), enabled);
    }

    public BlockPos stationPos() {
        return new BlockPos(stationX(), stationY(), stationZ());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
