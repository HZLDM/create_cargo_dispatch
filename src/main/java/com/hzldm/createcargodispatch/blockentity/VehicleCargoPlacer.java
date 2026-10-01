package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.CargoStructureHelper;
import com.hzldm.createcargodispatch.cargo.ConnectorTarget;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;

/**
 * 连接器模式：把货箱方块<b>直接放置到载具 SubLevel 的 plot grid 内</b>。
 *
 * <p>与「先独立物理化再传送焊接」的本质区别：货箱与载具同属一个 SubLevel，
 * 方块在 plot 内部世界中就是相邻的一体结构，无需约束/传送，载具移动货箱自然跟随。
 *
 * <p>原理：plot grid 是 inner Level 坐标空间中的固定区域，对 inner Level 调
 * setBlock 时坐标落在该区域会被 Sable 自动路由到载具 chunk，其 block change 钩子
 * 同步扩张物理碰撞/质量/包围盒。
 *
 * <p>位置：货箱底部中心坐在连接器顶面（connector.above(1)），
 * 长轴优先沿连接器 FACING 方向，该方向无空间再取另一水平轴。
 */
public final class VehicleCargoPlacer {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("CargoDispatch-VehiclePlacer");

    private VehicleCargoPlacer() {}

    /** 放置结果：blockCount 个 plot grid 坐标 + 主方块位置 */
    public record Result(List<BlockPos> gridPositions, BlockPos mainPos) {}

    /**
     * 在载具 plot 内放置货箱并注入数据/物品。
     *
     * @param target      检测到的连接器目标
     * @param stationType 起始站类型（决定专属货箱方块）
     * @param dims        货箱三维尺寸
     * @return 放置成功结果；空间不足/数据缺失返回 null
     */
    public static Result place(ServerLevel sl, ConnectorTarget target, StationType stationType,
                                CargoData cargoData, String cargoItemId, int cargoCount,
                                CargoDimensions dims) {
        Level inner = SubLevelScanner.getSubLevelInternalLevel(target.vehicle());
        if (inner == null) return null;
        BlockPos connector = target.connectorPos();

        // 长轴：选取「不越出本 SubLevel plot」的水平轴（占用方块放置时顶掉）
        Direction.Axis axis = chooseAvailableAxis(inner, connector, target.vehicle(), dims);
        if (axis == null) {
            LOGGER.info("连接器 {} 货箱两方向均越出载具 plot，无法放置", connector);
            return null;
        }

        // 货箱底部中心坐在连接器顶面
        BlockPos base = connector.above(1);
        List<BlockPos> positions = CargoStructureHelper.generateStructurePositions(base, axis, dims);

        BlockState state = CargoStructureHelper.getCargoBlock(stationType).get().defaultBlockState()
                .setValue(CargoBlock.HORIZONTAL_AXIS, axis);
        for (BlockPos p : positions) {
            CargoStructureHelper.placeOrEvict(inner, p, state, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        }

        // 货箱数据写入每个方块（装配/移动随 NBT 保留）
        for (BlockPos p : positions) {
            if (inner.getBlockEntity(p) instanceof CargoBlockEntity cbe) {
                cbe.setCargoData(cargoData);
                cbe.setMainBlock(p.equals(base));
                cbe.setChanged();
            }
        }

        // 注入物品到主方块并复制到其余方块（护目镜/查看在 plot 内一致）
        injectItems(inner, base, positions, cargoItemId, cargoCount);

        LOGGER.info("连接器模式：货箱已直接生成在载具 plot 内 base={} 轴={} 方块={}", base, axis, positions.size());
        return new Result(positions, base);
    }

    /**
     * 依次尝试「连接器 FACING 对应轴」→ 另一轴，返回「货箱不越出本 SubLevel plot」的第一个轴；
     * 两轴都越界才返回 null。
     * 公开：融合器（CargoAttacher）与接单放置共用同一套位置规则。
     *
     * 原理：连接器 FACING 为 N/S 时长轴走 Z，E/W 时走 X——货箱连接方向随放置朝向变换；
     * FACING 方向越界时再 fallback 另一轴。
     * <b>不再要求空间全空</b>：目标区有方块属正常情况，由 {@link CargoStructureHelper#placeOrEvict}
     * 统一顶掉并掉落，避免「侧面有方块 → 该轴被拒 → 货箱无法到位」。
     * 连接器 blockstate 随装配原样进入 plot，FACING 在此可读。
     */
    public static Direction.Axis chooseAvailableAxis(Level inner, BlockPos connector, Object vehicle,
                                                       CargoDimensions dims) {
        BlockPos base = connector.above(1);
        int[] plot = SubLevelScanner.readSubLevelGridXZBounds(vehicle);
        Direction.Axis preferred = readFacingAxis(inner, connector);
        Direction.Axis[] order = preferred == Direction.Axis.X
                ? new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}
                : new Direction.Axis[]{Direction.Axis.Z, Direction.Axis.X};
        for (Direction.Axis axis : order) {
            if (plot == null || fitsInPlot(base, axis, plot, dims)) {
                return axis;
            }
        }
        return null;
    }

    /** 读连接器 FACING 对应的水平轴；读取失败/旧数据默认 Z */
    private static Direction.Axis readFacingAxis(Level inner, BlockPos connector) {
        try {
            BlockState cs = inner.getBlockState(connector);
            if (cs.hasProperty(com.hzldm.createcargodispatch.block.CargoConnectorBlock.FACING)) {
                return cs.getValue(com.hzldm.createcargodispatch.block.CargoConnectorBlock.FACING).getAxis();
            }
        } catch (Throwable ignored) {
        }
        return Direction.Axis.Z;
    }

    /**
     * 货箱水平极值是否都在该 SubLevel plot 的 grid 方块边界内。
     * plot = {minBX, minBZ, maxBX(含), maxBZ(含)}。极值按实际尺寸。
     */
    private static boolean fitsInPlot(BlockPos base, Direction.Axis axis, int[] plot, CargoDimensions dims) {
        int hw = dims.halfWidth();
        int hl = dims.halfLength();
        if (axis == Direction.Axis.Z) {
            return base.getX() - hw >= plot[0] && base.getX() + hw <= plot[2]
                    && base.getZ() - hl >= plot[1] && base.getZ() + hl <= plot[3];
        }
        return base.getZ() - hw >= plot[1] && base.getZ() + hw <= plot[3]
                && base.getX() - hl >= plot[0] && base.getX() + hl <= plot[2];
    }

    /** 注入订单物品到主方块 inventory，再复制到其余货箱方块 */
    private static void injectItems(Level inner, BlockPos base, List<BlockPos> positions,
                                     String itemId, int count) {
        if (itemId == null || itemId.isEmpty()) return;
        Item item = byId(itemId);
        if (item == null || !(inner.getBlockEntity(base) instanceof CargoBlockEntity mainBE)) return;

        IItemHandler handler = mainBE.getWritableItemHandler();
        int remaining = count;
        int maxStack = item.getDefaultMaxStackSize();
        for (int i = 0; i < handler.getSlots() && remaining > 0; i++) {
            int n = Math.min(remaining, maxStack);
            ItemStack left = handler.insertItem(i, new ItemStack(item, n), false);
            remaining -= (n - left.getCount());
        }
        mainBE.setChanged();

        // 复制主 inventory 到其余方块（护目镜在任意方块上显示一致）
        SimpleContainer src = mainBE.getInventory();
        for (BlockPos p : positions) {
            if (p.equals(base)) continue;
            if (inner.getBlockEntity(p) instanceof CargoBlockEntity cbe) {
                copyInventory(src, cbe.getInventory());
                cbe.setChanged();
            }
        }
    }

    /**
     * 融合专用：把源货箱现有物品（任意堆叠）插入主方块，再复制到其余方块。
     * 与接单注入的区别：源是外部货箱的完整 inventory，而非单一物品×数量。
     */
    public static void injectExistingItems(Level inner, BlockPos base, List<BlockPos> positions,
                                             List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()
                || !(inner.getBlockEntity(base) instanceof CargoBlockEntity mainBE)) return;
        IItemHandler handler = mainBE.getWritableItemHandler();
        for (ItemStack s : stacks) {
            ItemHandlerHelper.insertItemStacked(handler, s.copy(), false);
        }
        mainBE.setChanged();

        // 复制主 inventory 到其余方块（护目镜在任意方块上显示一致）
        SimpleContainer src = mainBE.getInventory();
        for (BlockPos p : positions) {
            if (p.equals(base)) continue;
            if (inner.getBlockEntity(p) instanceof CargoBlockEntity cbe) {
                copyInventory(src, cbe.getInventory());
                cbe.setChanged();
            }
        }
    }

    private static void copyInventory(SimpleContainer src, SimpleContainer dst) {
        dst.clearContent();
        for (int i = 0; i < src.getContainerSize(); i++) {
            ItemStack s = src.getItem(i);
            if (!s.isEmpty()) dst.setItem(i, s.copy());
        }
    }

    private static Item byId(String id) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.parse(id));
        } catch (Exception e) {
            return null;
        }
    }
}
