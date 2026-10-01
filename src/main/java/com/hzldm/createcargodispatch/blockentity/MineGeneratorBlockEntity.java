package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿山货物生成器 BlockEntity
 * 原理：构造时直接设置 stationType=MINE，不需要 StationTypeResolver 推断
 */
public class MineGeneratorBlockEntity extends CargoGeneratorBlockEntity {

    public MineGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MINE_GENERATOR.get(), pos, state);
        setStationType(StationType.MINE);
    }
}
