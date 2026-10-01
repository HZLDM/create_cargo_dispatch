package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 伐木场专属货箱 BlockEntity
 * 原理：继承 CargoBlockEntity，绑定专属 BlockEntityType，提供 getStationType() 返回 LUMBER_YARD
 */
public class LumberYardCargoBlockEntity extends CargoBlockEntity {

    public LumberYardCargoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LUMBER_YARD_CARGO.get(), pos, state);
    }

    public StationType getStationType() {
        return StationType.LUMBER_YARD;
    }
}
