package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 货物检测器方块
 *
 * 原理：
 *  - 实现 EntityBlock 提供检测器 BlockEntity
 *  - 玩家右键交互时，向聊天框发送最近检测到的货物信息
 *  - FACING 用于检测器的显示朝向（正面图案对应面向方向）
 *
 * 结构放置（Jigsaw rotate/mirror）：
 *  - 手动重写 rotate()/mirror() 同步 FACING 属性，保证 Jigsaw 旋转结构时检测器朝向跟着转
 *    默认 Block.rotate 是空实现，会导致检测器 facing 与结构模板旋转错位
 */
public class CargoDetectorBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 红石脉冲态（货物成功提交后 20 tick 信号源） */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public CargoDetectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(LIT) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(LIT) ? 15 : 0;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

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
        return new CargoDetectorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        // 兼容所有 CargoDetectorBlockEntity 子类（LumberYard/Mine/Farm/Pasture/Metallurgy）
        return (lvl, pos, st, be) -> {
            if (be instanceof CargoDetectorBlockEntity detector) {
                detector.serverTick(lvl, pos);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CargoDetectorBlockEntity detector) {
            if (player.isShiftKeyDown()) {
                // 潜行+右键：切换检测范围可视化（绿色半透明框）
                boolean enabled = detector.toggleRangeDisplay();
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        enabled
                                ? "create_cargo_dispatch.detector.range_shown"
                                : "create_cargo_dispatch.detector.range_hidden"), true);
            } else {
                detector.onPlayerUse(player);
            }
        }
        return InteractionResult.CONSUME;
    }
}
