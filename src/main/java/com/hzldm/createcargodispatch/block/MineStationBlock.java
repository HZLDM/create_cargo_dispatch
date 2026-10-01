package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MineStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿山货运站方块
 * 原理：继承通用 CargoStationBlock，仅覆盖 newBlockEntity 返回矿山专属 BlockEntity
 */
public class MineStationBlock extends CargoStationBlock {

    public MineStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public StationType getStationType() {
        return StationType.MINE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MineStationBlockEntity(pos, state);
    }
}
