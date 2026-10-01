package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoConnectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.FarmCargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.FarmDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.FarmGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.FarmStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.LumberYardCargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.LumberYardDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.LumberYardGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.LumberYardStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MineCargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MineDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MineGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MineStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MetallurgyCargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MetallurgyDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MetallurgyGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.MetallurgyStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.PastureCargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.PastureDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.PastureGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.PastureStationBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * BlockEntity 注册类
 */
public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, CreateCargoDispatch.MODID);

    /** 货箱 BlockEntity（GENERIC 通用类型） */
    public static final Supplier<BlockEntityType<CargoBlockEntity>> CARGO = BLOCK_ENTITIES.register(
            "cargo",
            () -> BlockEntityType.Builder.of(CargoBlockEntity::new, ModBlocks.CARGO.get()).build(null)
    );

    // === 专属货箱 BlockEntity ===

    /** 伐木场专属货箱 BlockEntity */
    public static final Supplier<BlockEntityType<LumberYardCargoBlockEntity>> LUMBER_YARD_CARGO = BLOCK_ENTITIES.register(
            "lumber_yard_cargo",
            () -> BlockEntityType.Builder.of(LumberYardCargoBlockEntity::new, ModBlocks.LUMBER_YARD_CARGO.get()).build(null)
    );

    /** 矿场专属货箱 BlockEntity */
    public static final Supplier<BlockEntityType<MineCargoBlockEntity>> MINE_CARGO = BLOCK_ENTITIES.register(
            "mine_cargo",
            () -> BlockEntityType.Builder.of(MineCargoBlockEntity::new, ModBlocks.MINE_CARGO.get()).build(null)
    );

    /** 农场专属货箱 BlockEntity */
    public static final Supplier<BlockEntityType<FarmCargoBlockEntity>> FARM_CARGO = BLOCK_ENTITIES.register(
            "farm_cargo",
            () -> BlockEntityType.Builder.of(FarmCargoBlockEntity::new, ModBlocks.FARM_CARGO.get()).build(null)
    );

    /** 牧场专属货箱 BlockEntity */
    public static final Supplier<BlockEntityType<PastureCargoBlockEntity>> PASTURE_CARGO = BLOCK_ENTITIES.register(
            "pasture_cargo",
            () -> BlockEntityType.Builder.of(PastureCargoBlockEntity::new, ModBlocks.PASTURE_CARGO.get()).build(null)
    );

    /** 冶金厂专属货箱 BlockEntity */
    public static final Supplier<BlockEntityType<MetallurgyCargoBlockEntity>> METALLURGY_CARGO = BLOCK_ENTITIES.register(
            "metallurgy_cargo",
            () -> BlockEntityType.Builder.of(MetallurgyCargoBlockEntity::new, ModBlocks.METALLURGY_CARGO.get()).build(null)
    );

    /** 货物检测器 BlockEntity */
    public static final Supplier<BlockEntityType<CargoDetectorBlockEntity>> CARGO_DETECTOR = BLOCK_ENTITIES.register(
            "cargo_detector",
            () -> BlockEntityType.Builder.of(CargoDetectorBlockEntity::new, ModBlocks.CARGO_DETECTOR.get()).build(null)
    );

    /** 货物生成器 BlockEntity */
    public static final Supplier<BlockEntityType<CargoGeneratorBlockEntity>> CARGO_GENERATOR = BLOCK_ENTITIES.register(
            "cargo_generator",
            () -> BlockEntityType.Builder.of(CargoGeneratorBlockEntity::new, ModBlocks.CARGO_GENERATOR.get()).build(null)
    );

    /** 货运站 BlockEntity */
    public static final Supplier<BlockEntityType<CargoStationBlockEntity>> CARGO_STATION = BLOCK_ENTITIES.register(
            "cargo_station",
            () -> BlockEntityType.Builder.of(CargoStationBlockEntity::new, ModBlocks.CARGO_STATION.get()).build(null)
    );

    // === 伐木场专属 BlockEntity ===

    public static final Supplier<BlockEntityType<LumberYardStationBlockEntity>> LUMBER_YARD_STATION = BLOCK_ENTITIES.register(
            "lumber_yard_station",
            () -> BlockEntityType.Builder.of(LumberYardStationBlockEntity::new, ModBlocks.LUMBER_YARD_STATION.get()).build(null)
    );

    public static final Supplier<BlockEntityType<LumberYardGeneratorBlockEntity>> LUMBER_YARD_GENERATOR = BLOCK_ENTITIES.register(
            "lumber_yard_generator",
            () -> BlockEntityType.Builder.of(LumberYardGeneratorBlockEntity::new, ModBlocks.LUMBER_YARD_GENERATOR.get()).build(null)
    );

    public static final Supplier<BlockEntityType<LumberYardDetectorBlockEntity>> LUMBER_YARD_DETECTOR = BLOCK_ENTITIES.register(
            "lumber_yard_detector",
            () -> BlockEntityType.Builder.of(LumberYardDetectorBlockEntity::new, ModBlocks.LUMBER_YARD_DETECTOR.get()).build(null)
    );

    // === 矿山专属 BlockEntity ===

    public static final Supplier<BlockEntityType<MineStationBlockEntity>> MINE_STATION = BLOCK_ENTITIES.register(
            "mine_station",
            () -> BlockEntityType.Builder.of(MineStationBlockEntity::new, ModBlocks.MINE_STATION.get()).build(null)
    );

    public static final Supplier<BlockEntityType<MineGeneratorBlockEntity>> MINE_GENERATOR = BLOCK_ENTITIES.register(
            "mine_generator",
            () -> BlockEntityType.Builder.of(MineGeneratorBlockEntity::new, ModBlocks.MINE_GENERATOR.get()).build(null)
    );

    public static final Supplier<BlockEntityType<MineDetectorBlockEntity>> MINE_DETECTOR = BLOCK_ENTITIES.register(
            "mine_detector",
            () -> BlockEntityType.Builder.of(MineDetectorBlockEntity::new, ModBlocks.MINE_DETECTOR.get()).build(null)
    );

    // === 农场专属 BlockEntity ===

    public static final Supplier<BlockEntityType<FarmStationBlockEntity>> FARM_STATION = BLOCK_ENTITIES.register(
            "farm_station",
            () -> BlockEntityType.Builder.of(FarmStationBlockEntity::new, ModBlocks.FARM_STATION.get()).build(null)
    );

    public static final Supplier<BlockEntityType<FarmGeneratorBlockEntity>> FARM_GENERATOR = BLOCK_ENTITIES.register(
            "farm_generator",
            () -> BlockEntityType.Builder.of(FarmGeneratorBlockEntity::new, ModBlocks.FARM_GENERATOR.get()).build(null)
    );

    public static final Supplier<BlockEntityType<FarmDetectorBlockEntity>> FARM_DETECTOR = BLOCK_ENTITIES.register(
            "farm_detector",
            () -> BlockEntityType.Builder.of(FarmDetectorBlockEntity::new, ModBlocks.FARM_DETECTOR.get()).build(null)
    );

    // === 牧场专属 BlockEntity ===

    public static final Supplier<BlockEntityType<PastureStationBlockEntity>> PASTURE_STATION = BLOCK_ENTITIES.register(
            "pasture_station",
            () -> BlockEntityType.Builder.of(PastureStationBlockEntity::new, ModBlocks.PASTURE_STATION.get()).build(null)
    );

    public static final Supplier<BlockEntityType<PastureGeneratorBlockEntity>> PASTURE_GENERATOR = BLOCK_ENTITIES.register(
            "pasture_generator",
            () -> BlockEntityType.Builder.of(PastureGeneratorBlockEntity::new, ModBlocks.PASTURE_GENERATOR.get()).build(null)
    );

    public static final Supplier<BlockEntityType<PastureDetectorBlockEntity>> PASTURE_DETECTOR = BLOCK_ENTITIES.register(
            "pasture_detector",
            () -> BlockEntityType.Builder.of(PastureDetectorBlockEntity::new, ModBlocks.PASTURE_DETECTOR.get()).build(null)
    );

    // === 冶金厂专属 BlockEntity ===

    public static final Supplier<BlockEntityType<MetallurgyStationBlockEntity>> METALLURGY_STATION = BLOCK_ENTITIES.register(
            "metallurgy_station",
            () -> BlockEntityType.Builder.of(MetallurgyStationBlockEntity::new, ModBlocks.METALLURGY_STATION.get()).build(null)
    );

    public static final Supplier<BlockEntityType<MetallurgyGeneratorBlockEntity>> METALLURGY_GENERATOR = BLOCK_ENTITIES.register(
            "metallurgy_generator",
            () -> BlockEntityType.Builder.of(MetallurgyGeneratorBlockEntity::new, ModBlocks.METALLURGY_GENERATOR.get()).build(null)
    );

    public static final Supplier<BlockEntityType<MetallurgyDetectorBlockEntity>> METALLURGY_DETECTOR = BLOCK_ENTITIES.register(
            "metallurgy_detector",
            () -> BlockEntityType.Builder.of(MetallurgyDetectorBlockEntity::new, ModBlocks.METALLURGY_DETECTOR.get()).build(null)
    );

    /** 货箱连接器 BlockEntity */
    public static final Supplier<BlockEntityType<CargoConnectorBlockEntity>> CARGO_CONNECTOR = BLOCK_ENTITIES.register(
            "cargo_connector",
            () -> BlockEntityType.Builder.of(CargoConnectorBlockEntity::new, ModBlocks.CARGO_CONNECTOR.get()).build(null)
    );
}
