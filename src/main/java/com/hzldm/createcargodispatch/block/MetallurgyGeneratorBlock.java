package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MetallurgyGeneratorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂货物生成器方块
 * 原理：继承通用 CargoGeneratorBlock，仅覆盖 newBlockEntity 返回冶金厂专属 BlockEntity
 */
public class MetallurgyGeneratorBlock extends CargoGeneratorBlock {

    public MetallurgyGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MetallurgyGeneratorBlockEntity(pos, state);
    }
}
