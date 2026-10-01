package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.LumberYardStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场货运站方块
 * 原理：继承通用 CargoStationBlock，仅覆盖 newBlockEntity 返回伐木场专属 BlockEntity
 */
public class LumberYardStationBlock extends CargoStationBlock {

    public LumberYardStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public StationType getStationType() {
        return StationType.LUMBER_YARD;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LumberYardStationBlockEntity(pos, state);
    }
}
