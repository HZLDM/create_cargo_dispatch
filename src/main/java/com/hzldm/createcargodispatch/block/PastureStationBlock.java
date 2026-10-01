package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.PastureStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 牧场货运站方块
 * 原理：继承通用 CargoStationBlock，仅覆盖 newBlockEntity 返回牧场专属 BlockEntity
 */
public class PastureStationBlock extends CargoStationBlock {

    public PastureStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public StationType getStationType() {
        return StationType.PASTURE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PastureStationBlockEntity(pos, state);
    }
}
