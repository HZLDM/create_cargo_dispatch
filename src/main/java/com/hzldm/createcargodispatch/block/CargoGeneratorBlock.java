package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * 货物生成器方块
 *
 * 职责：
 *  - 仅负责生成货物（接单后在上方放置 3x3x9 货箱）
 *  - 不提供菜单入口（菜单由 CargoStationBlock 负责）
 *  - FACING 朝向决定货物延伸方向
 *
 * 原理：
 *  - 使用 BlockStateProperties.HORIZONTAL_FACING 属性（与 HorizontalDirectionalBlock 一致）
 *  - 货物沿 FACING 方向延伸 9 格，3x3 横截面居中于生成器
 *
 * 结构放置（Jigsaw rotate/mirror）：
 *  - 手动重写 rotate()/mirror() 同步 FACING 属性
 *    这样结构被 JigsawPlacement 随机旋转时，货箱生成方向（沿 FACING）会跟着结构整体一起转，
 *    不会出现"结构转了 90°，但货箱还按原 north 方向延伸，半条货箱撞进站体里"的问题
 */
public class CargoGeneratorBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 红石脉冲态（生成成功后 20 tick 信号源） */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public CargoGeneratorBlock(Properties properties) {
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
        return new CargoGeneratorBlockEntity(pos, state);
    }

    /**
     * 右键交互：普通右键打开出货设置界面；潜行右键切换红色范围框显示。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof CargoGeneratorBlockEntity generator) {
            if (player.isShiftKeyDown()) {
                if (!level.isClientSide()) {
                    boolean enabled = generator.toggleRangeDisplay();
                    player.displayClientMessage(Component.translatable(enabled
                            ? "create_cargo_dispatch.generator.range_shown"
                            : "create_cargo_dispatch.generator.range_hidden"), true);
                }
            } else if (level.isClientSide()) {
                openGeneratorSettingsScreen(pos);
            }
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 客户端：打开发货设置界面。
     * 标记 @OnlyIn(Dist.CLIENT)：服务端剥离后，Minecraft/GeneratorSettingsScreen 引用不会残留在字节码中。
     */
    @OnlyIn(Dist.CLIENT)
    private static void openGeneratorSettingsScreen(BlockPos pos) {
        net.minecraft.client.Minecraft.getInstance()
                .setScreen(new com.hzldm.createcargodispatch.client.GeneratorSettingsScreen(pos));
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        // 兼容所有 CargoGeneratorBlockEntity 子类（LumberYard/Mine/Farm/Pasture/Metallurgy）
        return (lvl, pos, st, be) -> {
            if (be instanceof CargoGeneratorBlockEntity generator) {
                generator.serverTick(lvl, pos);
            }
        };
    }
}
