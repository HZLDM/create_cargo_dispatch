package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 客户端→服务端：请求手动提交某条货箱
 *
 * 原理：
 *  - 玩家在提交页面点击某条货箱的「提交」按钮
 *  - 客户端回传 stationPos + detectorPos + SubLevelUuid(或controllerPos)
 *  - 服务端找到检测器 → 调用 forceSubmitCargo 触发收货逻辑
 *  - 完成后自动重新下发新的 SyncSubmitListPayload（刷新列表）
 */
public record SubmitCargoPayload(int stationX, int stationY, int stationZ,
                                 int detectorX, int detectorY, int detectorZ,
                                 boolean hasSubLevel,
                                 @org.jetbrains.annotations.Nullable UUID subLevelUuid,
                                 int controllerX, int controllerY, int controllerZ) implements CustomPacketPayload {

    public static final Type<SubmitCargoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "submit_cargo"));

    public static final StreamCodec<FriendlyByteBuf, SubmitCargoPayload> STREAM_CODEC =
            StreamCodec.of(SubmitCargoPayload::encode, SubmitCargoPayload::decode);

    public static void encode(FriendlyByteBuf buf, SubmitCargoPayload payload) {
        buf.writeInt(payload.stationX());
        buf.writeInt(payload.stationY());
        buf.writeInt(payload.stationZ());
        buf.writeInt(payload.detectorX());
        buf.writeInt(payload.detectorY());
        buf.writeInt(payload.detectorZ());
        buf.writeBoolean(payload.hasSubLevel());
        if (payload.hasSubLevel()) {
            buf.writeUUID(payload.subLevelUuid());
        } else {
            buf.writeInt(payload.controllerX());
            buf.writeInt(payload.controllerY());
            buf.writeInt(payload.controllerZ());
        }
    }

    public static SubmitCargoPayload decode(FriendlyByteBuf buf) {
        int sx = buf.readInt();
        int sy = buf.readInt();
        int sz = buf.readInt();
        int dx = buf.readInt();
        int dy = buf.readInt();
        int dz = buf.readInt();
        boolean hasSub = buf.readBoolean();
        UUID uuid = null;
        int cx = 0, cy = 0, cz = 0;
        if (hasSub) {
            uuid = buf.readUUID();
        } else {
            cx = buf.readInt();
            cy = buf.readInt();
            cz = buf.readInt();
        }
        return new SubmitCargoPayload(sx, sy, sz, dx, dy, dz, hasSub, uuid, cx, cy, cz);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
