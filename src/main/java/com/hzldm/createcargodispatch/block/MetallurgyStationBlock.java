package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MetallurgyStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂货运站方块
 * 原理：继承通用 CargoStationBlock，仅覆盖 newBlockEntity 返回冶金厂专属 BlockEntity
 */
public class MetallurgyStationBlock extends CargoStationBlock {

    public MetallurgyStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public StationType getStationType() {
        return StationType.METALLURGY;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MetallurgyStationBlockEntity(pos, state);
    }
}
