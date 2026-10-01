package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.PastureCargoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 牧场专属货箱方块
 * 原理：继承 CargoBlock，仅覆盖 newBlockEntity 返回牧场专属 CargoBlockEntity
 */
public class PastureCargoBlock extends CargoBlock {

    public PastureCargoBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PastureCargoBlockEntity(pos, state);
    }
}
