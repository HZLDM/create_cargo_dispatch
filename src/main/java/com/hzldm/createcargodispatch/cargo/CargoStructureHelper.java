package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 货箱多方块结构工具：按 {@link CargoDimensions} 生成结构坐标 / 检查空间。
 *
 * <p>结构锚点 = 主方块（base），位于截面「底部中心」、长轴「正中心」。
 * <ul>
 *   <li>长轴沿 Z：dz∈[-halfLen, halfLen]，dx∈[-halfW, halfW]，dy∈[0,height-1]</li>
 *   <li>长轴沿 X：dx 与 dz 角色互换</li>
 * </ul>
 * 坐标即结构尺寸，Sable 装配时据此识别真实大小。
 */
public final class CargoStructureHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Structure");

    private CargoStructureHelper() {
    }

    /**
     * 根据 StationType 获取对应的专属货箱方块 Supplier
     */
    public static Supplier<? extends CargoBlock> getCargoBlock(StationType type) {
        return switch (type) {
            case LUMBER_YARD -> com.hzldm.createcargodispatch.registry.ModBlocks.LUMBER_YARD_CARGO;
            case MINE -> com.hzldm.createcargodispatch.registry.ModBlocks.MINE_CARGO;
            case FARM -> com.hzldm.createcargodispatch.registry.ModBlocks.FARM_CARGO;
            case PASTURE -> com.hzldm.createcargodispatch.registry.ModBlocks.PASTURE_CARGO;
            case METALLURGY -> com.hzldm.createcargodispatch.registry.ModBlocks.METALLURGY_CARGO;
            case GENERIC -> com.hzldm.createcargodispatch.registry.ModBlocks.CARGO;
        };
    }

    /**
     * 生成指定尺寸结构的全部 BlockPos。
     *
     * @param base 主方块位置（锚点）
     * @param axis 长轴方向
     * @param dims 三维尺寸
     * @return blockCount 个坐标，第一个是主方块
     */
    public static List<BlockPos> generateStructurePositions(BlockPos base, Axis axis, CargoDimensions dims) {
        List<BlockPos> positions = new ArrayList<>(dims.blockCount());
        positions.add(base);

        int halfLen = dims.halfLength();
        int halfW = dims.halfWidth();

        if (axis == Axis.X) {
            // 长轴沿 X：截面在 Y-Z
            for (int dx = -halfLen; dx <= halfLen; dx++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    for (int dz = -halfW; dz <= halfW; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        positions.add(base.offset(dx, dy, dz));
                    }
                }
            }
        } else {
            // 长轴沿 Z（默认）：截面在 X-Y
            for (int dz = -halfLen; dz <= halfLen; dz++) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    for (int dy = 0; dy < dims.height(); dy++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        positions.add(base.offset(dx, dy, dz));
                    }
                }
            }
        }
        return positions;
    }

    /**
     * 检查结构空间是否全部可放置（非空气即占用）。主方块自身跳过（它已放置）。
     */
    public static boolean isSpaceAvailable(BlockGetter getter, BlockPos base, Axis axis, CargoDimensions dims) {
        int halfLen = dims.halfLength();
        int halfW = dims.halfWidth();

        if (axis == Axis.X) {
            for (int dx = -halfLen; dx <= halfLen; dx++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    for (int dz = -halfW; dz <= halfW; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (!getter.getBlockState(base.offset(dx, dy, dz)).isAir()) {
                            return false;
                        }
                    }
                }
            }
        } else {
            for (int dz = -halfLen; dz <= halfLen; dz++) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    for (int dy = 0; dy < dims.height(); dy++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        if (!getter.getBlockState(base.offset(dx, dy, dz)).isAir()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** BlockGetter 接口（避免直接依赖 Level） */
    public interface BlockGetter {
        BlockState getBlockState(BlockPos pos);
    }

    /**
     * 放置货箱方块（防挤压）：目标格若已有方块，先按机械挤开语义产出其全部掉落物，
     * 再放置新方块，避免直接 setBlock 静默覆盖导致原方块凭空消失。
     *
     * <p>原理（三步）：
     * <ol>
     *   <li>旧货箱：{@code onRemove} 只移除 BlockEntity、不掉 inventory，故逐槽 popResource
     *       弹出内部货物（专属货箱 BE 均继承 CargoBlockEntity，一处覆盖）；</li>
     *   <li>{@link Block#dropResources}：按战利品表掉落方块本身（NeoForge 触发 BlockDropsEvent，
     *       附属可干预）；流体等无战利品方块自然什么都不掉；</li>
     *   <li>setBlock 放置新方块。</li>
     * </ol>
     * 掉落物均以 ItemEntity 生成在当前 Level，SubLevel 内由 Sable 统一物理化。
     * <b>不走 {@link Level#destroyBlock}</b>：它会触发 playerWillDestroy（连锁破坏/取消订单，
     * 且无玩家时空指针），与机械挤开语义不符。掉落异常只记日志，不阻塞放置。
     */
    public static void placeOrEvict(Level level, BlockPos pos, BlockState newState, int flags) {
        BlockState old = level.getBlockState(pos);
        if (!old.isAir()) {
            BlockEntity be = old.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            try {
                if (be instanceof CargoBlockEntity cargoBE) {
                    SimpleContainer inv = cargoBE.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        ItemStack stack = inv.getItem(i);
                        if (!stack.isEmpty()) {
                            Block.popResource(level, pos, stack.copy());
                        }
                    }
                }
                Block.dropResources(old, level, pos, be);
            } catch (Exception e) {
                LOGGER.warn("顶出 {} 处方块 {} 时掉落失败（不影响放置）", pos, old, e);
            }
        }
        level.setBlock(pos, newState, flags);
    }
}
