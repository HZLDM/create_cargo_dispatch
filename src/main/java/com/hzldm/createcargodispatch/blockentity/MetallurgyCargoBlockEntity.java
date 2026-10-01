package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 冶金厂专属货箱 BlockEntity
 * 原理：继承 CargoBlockEntity，绑定专属 BlockEntityType，提供 getStationType() 返回 METALLURGY
 */
public class MetallurgyCargoBlockEntity extends CargoBlockEntity {

    public MetallurgyCargoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.METALLURGY_CARGO.get(), pos, state);
    }

    public StationType getStationType() {
        return StationType.METALLURGY;
    }
}
