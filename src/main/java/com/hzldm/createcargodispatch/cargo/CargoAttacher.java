package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.VehicleCargoPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 货箱融合器：把外部货箱（地面静态方块 / 独立物理 SubLevel）吸附进载具 plot，
 * 与载具<b>融合为同一个 SubLevel</b>。
 *
 * <h3>为什么用融合而不是 FixedConstraint</h3>
 * 同体结构是刚性一体（物理引擎层面无约束误差），与「断开=切割」构成对称操作：
 * <pre>
 *   融合（红石ON）：外部货箱 → 载具 plot
 *   切割（红石OFF）：载具 plot → 新独立 SubLevel
 * </pre>
 *
 * <h3>流程与失败安全</h3>
 * <ol>
 *   <li>统一位置校验：选轴（不越出 plot；占用方块顶掉并掉落）；</li>
 *   <li>plot 内放标准货箱方块，恢复 cargoData 与全部物品（数据先行）；</li>
 *   <li>再删除源货箱（世界方块 / 源 SubLevel）。</li>
 * </ol>
 * 货箱两方向均越出 plot 时返回 null，源货箱一个不动，调用方下 tick 自动重试。
 * 融合产物与接单放置（VehicleCargoPlacer）完全一致，断开/收货/放弃路径无需感知差异。
 */
public final class CargoAttacher {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Attacher");

    /** 融合结果（携带 cargoData 供策略层同步订单 UUID） */
    public record MergeResult(CargoData cargoData) {}

    private CargoAttacher() {}

    /** 融合地面静态货箱 */
    public static MergeResult mergeStatic(ServerLevel sl, Object vehicle,
                                            BlockPos connector, BlockPos staticMain) {
        CargoData cargoData = CargoManager.query(staticMain);
        if (cargoData == null) return null;

        // 提取源主方块全部物品（1.21 Container 不再实现 Iterable，用索引遍历）
        List<ItemStack> stacks = new ArrayList<>();
        if (sl.getBlockEntity(staticMain) instanceof CargoBlockEntity cbe) {
            SimpleContainer src = cbe.getInventory();
            for (int i = 0; i < src.getContainerSize(); i++) {
                ItemStack s = src.getItem(i);
                if (!s.isEmpty()) stacks.add(s.copy());
            }
        }
        List<BlockPos> allBlocks = CargoManager.collectStaticCargoBlocks(staticMain);

        MergeResult r = placeIntoPlot(sl, vehicle, connector, cargoData, stacks);
        if (r == null) return null;

        // plot 已有完整副本：删除世界源方块并清理全部静态索引
        for (BlockPos p : allBlocks) {
            sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        CargoManager.unregisterStaticStructure(staticMain, allBlocks);
        LOGGER.info("[CargoAttach] 静态货箱 {} 已融合进载具", staticMain);
        return r;
    }

    /** 融合独立物理货箱 SubLevel */
    public static MergeResult mergeSubLevel(ServerLevel sl, Object vehicle,
                                              BlockPos connector, Object cargoSub) {
        if (cargoSub == vehicle) return null; // 永不融合连接器自身所在载具
        UUID cuuid = SubLevelScanner.getSubLevelUuid(cargoSub);
        if (cuuid == null) return null;
        // 安全前置：附着标记表示该 SubLevel 是「货箱已融入的另一艘载具」。
        // 若误融合，placeIntoPlot 后的 removeSubLevel 会把整艘载具物理删除（严重事故），必须拒绝。
        if (CargoManager.isAttachedVehicleCargo(cuuid)) {
            LOGGER.info("[CargoAttach] 目标 {} 是载货载具（货箱已被其他连接器占用），跳过", cuuid);
            return null;
        }
        CargoData cargoData = CargoManager.getCargoDataBySubLevel(cuuid);
        if (cargoData == null) return null;
        // 物品取注册时缓存快照（最可靠，不依赖源 inner BE 坐标）
        List<ItemStack> stacks = CargoManager.getInventorySnapshot(cuuid);

        MergeResult r = placeIntoPlot(sl, vehicle, connector, cargoData, stacks);
        if (r == null) return null;

        // plot 已有完整副本：删除源 SubLevel（物理 + 索引）
        if (!SubLevelScanner.removeSubLevel(sl, cargoSub)) {
            LOGGER.error("[CargoAttach] 源 SubLevel 物理删除失败 cuuid={}（plot 内已有副本）", cuuid);
        }
        CargoManager.unregisterSubLevel(cuuid);
        LOGGER.info("[CargoAttach] 独立货箱 {} 已融合进载具", cuuid);
        return r;
    }

    /** 在载具 plot 内放置货箱并恢复全部数据；无可用空间返回 null（源不动） */
    private static MergeResult placeIntoPlot(ServerLevel sl, Object vehicle, BlockPos connector,
                                               CargoData cargoData, List<ItemStack> stacks) {
        CargoDimensions dims = cargoData.getDimensions();
        Level inner = SubLevelScanner.getSubLevelInternalLevel(vehicle);
        UUID vehicleUuid = SubLevelScanner.getSubLevelUuid(vehicle);
        if (inner == null || vehicleUuid == null) return null;

        // 位置校验：货箱两方向均越出 plot 才失败（占用方块放置时顶掉并掉落）
        Direction.Axis axis = VehicleCargoPlacer.chooseAvailableAxis(inner, connector, vehicle, dims);
        if (axis == null) {
            LOGGER.info("[CargoAttach] 货箱两方向均越出 plot，跳过（下 tick 自动重试）");
            return null;
        }

        BlockPos base = connector.above(1);
        List<BlockPos> positions = CargoStructureHelper.generateStructurePositions(base, axis, dims);

        // 放标准货箱方块（源站类型决定专属外观）
        StationType stationType = StationType.byId(cargoData.getSourceStationType());
        BlockState state = CargoStructureHelper.getCargoBlock(stationType).get().defaultBlockState()
                .setValue(CargoBlock.HORIZONTAL_AXIS, axis);
        for (BlockPos p : positions) {
            CargoStructureHelper.placeOrEvict(inner, p, state, Block.UPDATE_ALL);
        }

        // cargoData 与主方块标记写入每个方块
        for (BlockPos p : positions) {
            if (inner.getBlockEntity(p) instanceof CargoBlockEntity cbe) {
                cbe.setCargoData(cargoData);
                cbe.setMainBlock(p.equals(base));
                cbe.setChanged();
            }
        }

        // 物品注入主方块并复制到其余方块
        VehicleCargoPlacer.injectExistingItems(inner, base, positions, stacks);

        // 注册附着（inventory 取 plot 主方块 BE，保证快照真源一致）
        SimpleContainer inventory = inner.getBlockEntity(base) instanceof CargoBlockEntity cbe
                ? cbe.getInventory() : null;
        CargoManager.registerAttachedCargo(vehicleUuid, base, positions, inventory, cargoData);
        return new MergeResult(cargoData);
    }
}
