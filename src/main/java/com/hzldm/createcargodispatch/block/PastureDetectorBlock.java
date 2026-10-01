package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.PastureDetectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 牧场货物检测器方块
 * 原理：继承通用 CargoDetectorBlock，仅覆盖 newBlockEntity 返回牧场专属 BlockEntity
 */
public class PastureDetectorBlock extends CargoDetectorBlock {

    public PastureDetectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PastureDetectorBlockEntity(pos, state);
    }
}
