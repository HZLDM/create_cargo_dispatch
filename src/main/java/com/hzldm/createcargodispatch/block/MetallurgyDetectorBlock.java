package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.MetallurgyDetectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂货物检测器方块
 * 原理：继承通用 CargoDetectorBlock，仅覆盖 newBlockEntity 返回冶金厂专属 BlockEntity
 */
public class MetallurgyDetectorBlock extends CargoDetectorBlock {

    public MetallurgyDetectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MetallurgyDetectorBlockEntity(pos, state);
    }
}
