package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 牧场货物检测器 BlockEntity
 * 原理：构造时直接设置 stationType=PASTURE，不需要 StationTypeResolver 推断
 */
public class PastureDetectorBlockEntity extends CargoDetectorBlockEntity {

    public PastureDetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PASTURE_DETECTOR.get(), pos, state);
        setStationType(StationType.PASTURE);
    }
}
