package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农场专属货箱 BlockEntity
 * 原理：继承 CargoBlockEntity，绑定专属 BlockEntityType，提供 getStationType() 返回 FARM
 */
public class FarmCargoBlockEntity extends CargoBlockEntity {

    public FarmCargoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FARM_CARGO.get(), pos, state);
    }

    public StationType getStationType() {
        return StationType.FARM;
    }
}
