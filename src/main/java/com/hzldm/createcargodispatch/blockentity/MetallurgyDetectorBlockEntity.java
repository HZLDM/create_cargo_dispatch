package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂货物检测器 BlockEntity
 * 原理：构造时直接设置 stationType=METALLURGY，不需要 StationTypeResolver 推断
 */
public class MetallurgyDetectorBlockEntity extends CargoDetectorBlockEntity {

    public MetallurgyDetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.METALLURGY_DETECTOR.get(), pos, state);
        setStationType(StationType.METALLURGY);
    }
}
