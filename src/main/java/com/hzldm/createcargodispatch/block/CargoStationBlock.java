package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationGroupHelper;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 货运站方块
 *
 * 职责：
 *  - 仅提供接单 UI 入口（右键打开菜单）
 *  - 不负责生成货物（由最近的 CargoGeneratorBlock 执行）
 *  - FACING 决定控制面板/屏幕的朝向显示
 *
 * 结构放置（Jigsaw rotate/mirror）：
 *  - 未继承 HorizontalDirectionalBlock（避免 createBlockStateDefinition 重复注册 FACING）
 *  - 手动重写 rotate()/mirror()，保证 Jigsaw 结构自然生成时被随机旋转后，FACING 与整体结构朝向同步
 *    否则 Block.rotate 默认实现直接 return 原 state，导致站方块 facing 永远是 NBT 原始值，和结构整体旋转错位
 */
public class CargoStationBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public CargoStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * 本站方块对应的货运类型（5 个专属子类重写）；放置冲突预检使用，BE 创建前即可判定
     */
    public StationType getStationType() {
        return StationType.GENERIC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        // 放置前冲突预检：同类型站过近、或放下后会与他站编号重复 → 拒绝放置
        // 原理：返回 null 时 BlockItem 中止放置流程，物品不消耗；客户端预测同样返回 null，双端一致
        if (StationGroupHelper.findStationPlacementConflict(level, pos, getStationType()) != null) {
            if (!level.isClientSide() && context.getPlayer() instanceof ServerPlayer player) {
                player.displayClientMessage(
                        Component.translatable("create_cargo_dispatch.station.place_too_close")
                                .withStyle(ChatFormatting.RED), true);
            }
            return null;
        }
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
        return new CargoStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 货运站无 tick 逻辑
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CargoStationBlockEntity station
                && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(station, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }
}
