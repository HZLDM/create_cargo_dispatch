package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场货物检测器 BlockEntity
 * 原理：构造时直接设置 stationType=LUMBER_YARD，不需要 StationTypeResolver 推断
 */
public class LumberYardDetectorBlockEntity extends CargoDetectorBlockEntity {

    public LumberYardDetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LUMBER_YARD_DETECTOR.get(), pos, state);
        setStationType(StationType.LUMBER_YARD);
    }
}
