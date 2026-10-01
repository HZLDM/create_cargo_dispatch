package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.FarmGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农场货物生成器方块
 * 原理：继承通用 CargoGeneratorBlock，仅覆盖 newBlockEntity 返回农场专属 BlockEntity
 */
public class FarmGeneratorBlock extends CargoGeneratorBlock {

    public FarmGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FarmGeneratorBlockEntity(pos, state);
    }
}
