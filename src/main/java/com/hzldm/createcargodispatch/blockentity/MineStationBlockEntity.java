package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿山货运站 BlockEntity
 * 原理：构造时直接设置 stationType=MINE，不需要 StationTypeResolver 推断
 */
public class MineStationBlockEntity extends CargoStationBlockEntity {

    public MineStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MINE_STATION.get(), pos, state);
        setStationType(StationType.MINE);
    }
}
