package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场货运站 BlockEntity
 * 原理：构造时直接设置 stationType=LUMBER_YARD，不需要 StationTypeResolver 推断
 */
public class LumberYardStationBlockEntity extends CargoStationBlockEntity {

    public LumberYardStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LUMBER_YARD_STATION.get(), pos, state);
        setStationType(StationType.LUMBER_YARD);
    }
}
