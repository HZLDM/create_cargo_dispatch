package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农场货物生成器 BlockEntity
 * 原理：构造时直接设置 stationType=FARM，不需要 StationTypeResolver 推断
 */
public class FarmGeneratorBlockEntity extends CargoGeneratorBlockEntity {

    public FarmGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FARM_GENERATOR.get(), pos, state);
        setStationType(StationType.FARM);
    }
}
