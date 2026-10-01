package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.PastureGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 牧场货物生成器方块
 * 原理：继承通用 CargoGeneratorBlock，仅覆盖 newBlockEntity 返回牧场专属 BlockEntity
 */
public class PastureGeneratorBlock extends CargoGeneratorBlock {

    public PastureGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PastureGeneratorBlockEntity(pos, state);
    }
}
