package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.FarmStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农场货运站方块
 * 原理：继承通用 CargoStationBlock，仅覆盖 newBlockEntity 返回农场专属 BlockEntity
 */
public class FarmStationBlock extends CargoStationBlock {

    public FarmStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public StationType getStationType() {
        return StationType.FARM;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FarmStationBlockEntity(pos, state);
    }
}
