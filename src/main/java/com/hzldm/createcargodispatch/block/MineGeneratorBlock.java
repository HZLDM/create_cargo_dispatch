package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MineGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿山货物生成器方块
 * 原理：继承通用 CargoGeneratorBlock，仅覆盖 newBlockEntity 返回矿山专属 BlockEntity
 */
public class MineGeneratorBlock extends CargoGeneratorBlock {

    public MineGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MineGeneratorBlockEntity(pos, state);
    }
}
