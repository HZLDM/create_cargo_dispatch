package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.LumberYardDetectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场货物检测器方块
 * 原理：继承通用 CargoDetectorBlock，仅覆盖 newBlockEntity 返回伐木场专属 BlockEntity
 */
public class LumberYardDetectorBlock extends CargoDetectorBlock {

    public LumberYardDetectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LumberYardDetectorBlockEntity(pos, state);
    }
}
