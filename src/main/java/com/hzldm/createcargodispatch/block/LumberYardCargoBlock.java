package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.LumberYardCargoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场专属货箱方块
 * 原理：继承 CargoBlock，仅覆盖 newBlockEntity 返回伐木场专属 CargoBlockEntity
 */
public class LumberYardCargoBlock extends CargoBlock {

    public LumberYardCargoBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LumberYardCargoBlockEntity(pos, state);
    }
}
