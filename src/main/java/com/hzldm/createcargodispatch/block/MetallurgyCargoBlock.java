package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MetallurgyCargoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂专属货箱方块
 * 原理：继承 CargoBlock，仅覆盖 newBlockEntity 返回冶金厂专属 CargoBlockEntity
 */
public class MetallurgyCargoBlock extends CargoBlock {

    public MetallurgyCargoBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MetallurgyCargoBlockEntity(pos, state);
    }
}
