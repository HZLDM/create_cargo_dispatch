package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.LumberYardGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场货物生成器方块
 * 原理：继承通用 CargoGeneratorBlock，仅覆盖 newBlockEntity 返回伐木场专属 BlockEntity
 */
public class LumberYardGeneratorBlock extends CargoGeneratorBlock {

    public LumberYardGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LumberYardGeneratorBlockEntity(pos, state);
    }
}
