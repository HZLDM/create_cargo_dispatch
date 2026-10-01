package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：调试货箱编辑页初始数据（编辑的是主手调试物品，无方块坐标）。
 * targets = 玩家所属公司已连接站点中、与货物源类型不同的全部站点（循环选择目标用）。
 */
public record SyncDebugCargoPayload(String orderId, String sourceTypeId, String cargoItemId,
                                    int dimW, int dimH, int dimL,
                                    int selectedTarget, List<TargetEntry> targets)
        implements CustomPacketPayload {

    public static final Type<SyncDebugCargoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "sync_debug_cargo"));

    public static final StreamCodec<FriendlyByteBuf, SyncDebugCargoPayload> STREAM_CODEC =
            StreamCodec.of(SyncDebugCargoPayload::encode, SyncDebugCargoPayload::decode);

    /** 可选目标站（坐标 + 类型 + 站点编号） */
    public record TargetEntry(int x, int y, int z, String typeId, String stationId) {
        static TargetEntry decode(FriendlyByteBuf buf) {
            return new TargetEntry(buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(32), buf.readUtf(48));
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeUtf(typeId, 32);
            buf.writeUtf(stationId == null ? "" : stationId, 48);
        }
    }

    private static void encode(FriendlyByteBuf buf, SyncDebugCargoPayload p) {
        buf.writeUtf(p.orderId == null ? "" : p.orderId, 32);
        buf.writeUtf(p.sourceTypeId == null ? "generic" : p.sourceTypeId, 32);
        buf.writeUtf(p.cargoItemId == null ? "" : p.cargoItemId, 64);
        buf.writeVarInt(p.dimW);
        buf.writeVarInt(p.dimH);
        buf.writeVarInt(p.dimL);
        buf.writeInt(p.selectedTarget);
        buf.writeVarInt(p.targets.size());
        for (TargetEntry e : p.targets) e.encode(buf);
    }

    private static SyncDebugCargoPayload decode(FriendlyByteBuf buf) {
        String orderId = buf.readUtf(32);
        String sourceTypeId = buf.readUtf(32);
        String cargoItemId = buf.readUtf(64);
        int dimW = buf.readVarInt();
        int dimH = buf.readVarInt();
        int dimL = buf.readVarInt();
        int selected = buf.readInt();
        int count = buf.readVarInt();
        List<TargetEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(TargetEntry.decode(buf));
        return new SyncDebugCargoPayload(orderId, sourceTypeId, cargoItemId,
                dimW, dimH, dimL, selected, list);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
