package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.CargoConnectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 货箱连接器方块
 *
 * 原理：
 *  - 受红石控制：POWERED=true 表示有红石信号，是连接器启用状态的唯一真源
 *  - BE 直接读该 blockstate（不再另存/双写）；装配载具时随方块保留
 *  - 红石激活：连接器可接单；sable$tick 也会尝试吸附附近地面静止货箱
 *  - 红石断开：sable$tick 自动解除 attach 吸附（由连接器 BE 每 tick 检测）
 *
 * 红石触发：重写 neighborChanged，相邻方块变化时检查信号，
 * POWERED 与实际不一致则一次 setBlock 同步。
 */
public class CargoConnectorBlock extends Block implements EntityBlock {

    /** 红石通电状态属性 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** 放置朝向（正面面向玩家；与检测器/生成器一致用 HORIZONTAL_FACING） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public CargoConnectorBlock(Properties properties) {
        super(properties);
        // 默认为未通电；FACING 默认 north
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, FACING);
    }

    /** 放置时正面朝向玩家 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** 结构/Jigsaw 旋转时同步 FACING，避免方块朝向与结构整体旋转错位 */
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CargoConnectorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof CargoConnectorBlockEntity connector) {
                connector.serverTick(lvl, pos, st);
            }
        };
    }

    /**
     * 玩家破坏连接器前：若正连着货箱，先执行与红石 OFF 相同的切割，
     * 使货箱在原位成为独立 SubLevel（而非困死在载具 plot 内）。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            com.hzldm.createcargodispatch.blockentity.CargoConnectorStrategy
                    .onConnectorDestroyed(level, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 邻居方块变更回调：检测红石信号变化
     *
     * 原理：
     *  - 当相邻方块（包括上方红石线、相邻拉杆等）变化时触发
     *  - 读取 level.hasNeighborSignal(pos) 判断是否有任何方向的红石信号
     *  - 若当前 POWERED 状态与实际信号不一致，更新 BlockState 并通知 BlockEntity
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide()) return;

        boolean actuallyPowered = level.hasNeighborSignal(pos);
        boolean currentlyPowered = state.getValue(POWERED);

        if (actuallyPowered != currentlyPowered) {
            // 一次 setBlock 同步 POWERED：BE.isEnabled() 直接读它，sable$tick 下帧自动响应
            level.setBlock(pos, state.setValue(POWERED, actuallyPowered), Block.UPDATE_ALL_IMMEDIATE);
        }
    }

    /**
     * 方块放置时检查初始红石状态
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide()) return;
        if (oldState.getBlock() == state.getBlock()) return; // 同方块替换无需处理

        boolean actuallyPowered = level.hasNeighborSignal(pos);
        if (actuallyPowered != state.getValue(POWERED)) {
            // 放置瞬间校正初始 POWERED；BE 直接读 blockstate，无需额外通知
            level.setBlock(pos, state.setValue(POWERED, actuallyPowered), Block.UPDATE_ALL_IMMEDIATE);
        }
    }
}
