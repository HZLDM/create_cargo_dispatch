package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.block.CargoConnectorBlock;
import com.hzldm.createcargodispatch.block.CargoDetectorBlock;
import com.hzldm.createcargodispatch.block.CargoGeneratorBlock;
import com.hzldm.createcargodispatch.block.CargoStationBlock;
import com.hzldm.createcargodispatch.block.FarmCargoBlock;
import com.hzldm.createcargodispatch.block.FarmDetectorBlock;
import com.hzldm.createcargodispatch.block.FarmGeneratorBlock;
import com.hzldm.createcargodispatch.block.FarmStationBlock;
import com.hzldm.createcargodispatch.block.LumberYardCargoBlock;
import com.hzldm.createcargodispatch.block.LumberYardDetectorBlock;
import com.hzldm.createcargodispatch.block.LumberYardGeneratorBlock;
import com.hzldm.createcargodispatch.block.LumberYardStationBlock;
import com.hzldm.createcargodispatch.block.MineCargoBlock;
import com.hzldm.createcargodispatch.block.MineDetectorBlock;
import com.hzldm.createcargodispatch.block.MineGeneratorBlock;
import com.hzldm.createcargodispatch.block.MineStationBlock;
import com.hzldm.createcargodispatch.block.MetallurgyCargoBlock;
import com.hzldm.createcargodispatch.block.MetallurgyDetectorBlock;
import com.hzldm.createcargodispatch.block.MetallurgyGeneratorBlock;
import com.hzldm.createcargodispatch.block.MetallurgyStationBlock;
import com.hzldm.createcargodispatch.block.PastureCargoBlock;
import com.hzldm.createcargodispatch.block.PastureDetectorBlock;
import com.hzldm.createcargodispatch.block.PastureGeneratorBlock;
import com.hzldm.createcargodispatch.block.PastureStationBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 方块注册类。
 *
 * <p>两类属性：
 * <ul>
 *   <li><b>普通机器</b>（货箱/生成器/检测器/货运站等）：{@link #machineProps}
 *       硬度 -1，<b>不可破坏</b>；</li>
 *   <li><b>货物连接器</b>：{@link #connectorProps} 铁块级硬度（可被航空学/Sable 装配）。</li>
 * </ul>
 *
 * <p><b>为什么只有连接器不能用硬度 -1：</b>航空学拖拽装配的
 * {@code SimAssemblyContraption.movementAllowed} 硬性要求
 * {@code state.getDestroySpeed() != -1}，该判定不经过任何注册回调、无法绕过；
 * 连接器需要随载具物理化，故给它可破坏硬度，其余机器保持不可破坏。
 */
public class ModBlocks {

    /** 连接器破坏硬度（铁块级，可被航空学/Sable 装配） */
    private static final float CONNECTOR_DESTROY_TIME = 5.0F;
    /** 机器/连接器统一爆炸抗性（高到实际完全防爆） */
    private static final float EXPLODE_RESISTANCE = 3600000.0F;

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK, CreateCargoDispatch.MODID);

    /** 普通机器属性：不可破坏（硬度 -1）、防爆 */
    private static BlockBehaviour.Properties machineProps(MapColor color) {
        return BlockBehaviour.Properties.of()
                .strength(-1.0F, EXPLODE_RESISTANCE)
                .mapColor(color);
    }

    /**
     * 货物连接器属性：可被装配（铁块级硬度）、防爆。
     * requiresCorrectToolForDrops：仅铁镐及以上（needs_iron_tool 标签）挖掘才掉落，
     * 同时让 Sable 物理化结构按原版工具规则放行铁镐破坏。
     */
    private static BlockBehaviour.Properties connectorProps(MapColor color) {
        return BlockBehaviour.Properties.of()
                .strength(CONNECTOR_DESTROY_TIME, EXPLODE_RESISTANCE)
                .mapColor(color)
                .requiresCorrectToolForDrops();
    }

    /** 货箱方块（GENERIC 通用类型）：物理化货物，只读 inventory */
    public static final Supplier<CargoBlock> CARGO = BLOCKS.register(
            "cargo",
            () -> new CargoBlock(machineProps(MapColor.METAL).noOcclusion())
    );

    // === 专属货箱方块 ===

    /** 伐木场专属货箱：物理化后由 Sable 识别 3x3x9 真实大小 */
    public static final Supplier<LumberYardCargoBlock> LUMBER_YARD_CARGO = BLOCKS.register(
            "lumber_yard_cargo",
            () -> new LumberYardCargoBlock(machineProps(MapColor.WOOD).noOcclusion())
    );

    /** 矿场专属货箱：物理化后由 Sable 识别 3x3x9 真实大小 */
    public static final Supplier<MineCargoBlock> MINE_CARGO = BLOCKS.register(
            "mine_cargo",
            () -> new MineCargoBlock(machineProps(MapColor.STONE).noOcclusion())
    );

    /** 农场专属货箱：物理化后由 Sable 识别 3x3x9 真实大小 */
    public static final Supplier<FarmCargoBlock> FARM_CARGO = BLOCKS.register(
            "farm_cargo",
            () -> new FarmCargoBlock(machineProps(MapColor.PLANT).noOcclusion())
    );

    /** 牧场专属货箱：物理化后由 Sable 识别 3x3x9 真实大小 */
    public static final Supplier<PastureCargoBlock> PASTURE_CARGO = BLOCKS.register(
            "pasture_cargo",
            () -> new PastureCargoBlock(machineProps(MapColor.WOOL).noOcclusion())
    );

    /** 冶金厂专属货箱：物理化后由 Sable 识别 3x3x9 真实大小 */
    public static final Supplier<MetallurgyCargoBlock> METALLURGY_CARGO = BLOCKS.register(
            "metallurgy_cargo",
            () -> new MetallurgyCargoBlock(machineProps(MapColor.METAL).noOcclusion())
    );

    /** 货物检测器：自动关联最近货运站编号 */
    public static final Supplier<CargoDetectorBlock> CARGO_DETECTOR = BLOCKS.register(
            "cargo_detector",
            () -> new CargoDetectorBlock(machineProps(MapColor.STONE))
    );

    /** 货物生成器：自动关联最近货运站编号 */
    public static final Supplier<CargoGeneratorBlock> CARGO_GENERATOR = BLOCKS.register(
            "cargo_generator",
            () -> new CargoGeneratorBlock(machineProps(MapColor.STONE))
    );

    /** 货运站：接单 UI 入口，随机生成编号 */
    public static final Supplier<CargoStationBlock> CARGO_STATION = BLOCKS.register(
            "cargo_station",
            () -> new CargoStationBlock(machineProps(MapColor.WOOD))
    );

    // === 伐木场专属方块 ===

    /** 伐木场货运站：构造时即为 LUMBER_YARD 类型 */
    public static final Supplier<LumberYardStationBlock> LUMBER_YARD_STATION = BLOCKS.register(
            "lumber_yard_station",
            () -> new LumberYardStationBlock(machineProps(MapColor.WOOD))
    );

    /** 伐木场货物生成器 */
    public static final Supplier<LumberYardGeneratorBlock> LUMBER_YARD_GENERATOR = BLOCKS.register(
            "lumber_yard_generator",
            () -> new LumberYardGeneratorBlock(machineProps(MapColor.WOOD))
    );

    /** 伐木场货物检测器 */
    public static final Supplier<LumberYardDetectorBlock> LUMBER_YARD_DETECTOR = BLOCKS.register(
            "lumber_yard_detector",
            () -> new LumberYardDetectorBlock(machineProps(MapColor.WOOD))
    );

    // === 矿山专属方块 ===

    /** 矿山货运站：构造时即为 MINE 类型 */
    public static final Supplier<MineStationBlock> MINE_STATION = BLOCKS.register(
            "mine_station",
            () -> new MineStationBlock(machineProps(MapColor.STONE))
    );

    /** 矿山货物生成器 */
    public static final Supplier<MineGeneratorBlock> MINE_GENERATOR = BLOCKS.register(
            "mine_generator",
            () -> new MineGeneratorBlock(machineProps(MapColor.STONE))
    );

    /** 矿山货物检测器 */
    public static final Supplier<MineDetectorBlock> MINE_DETECTOR = BLOCKS.register(
            "mine_detector",
            () -> new MineDetectorBlock(machineProps(MapColor.STONE))
    );

    // === 农场专属方块 ===

    /** 农场货运站：构造时即为 FARM 类型 */
    public static final Supplier<FarmStationBlock> FARM_STATION = BLOCKS.register(
            "farm_station",
            () -> new FarmStationBlock(machineProps(MapColor.PLANT))
    );

    /** 农场货物生成器 */
    public static final Supplier<FarmGeneratorBlock> FARM_GENERATOR = BLOCKS.register(
            "farm_generator",
            () -> new FarmGeneratorBlock(machineProps(MapColor.PLANT))
    );

    /** 农场货物检测器 */
    public static final Supplier<FarmDetectorBlock> FARM_DETECTOR = BLOCKS.register(
            "farm_detector",
            () -> new FarmDetectorBlock(machineProps(MapColor.PLANT))
    );

    // === 牧场专属方块 ===

    /** 牧场货运站：构造时即为 PASTURE 类型 */
    public static final Supplier<PastureStationBlock> PASTURE_STATION = BLOCKS.register(
            "pasture_station",
            () -> new PastureStationBlock(machineProps(MapColor.WOOL))
    );

    /** 牧场货物生成器 */
    public static final Supplier<PastureGeneratorBlock> PASTURE_GENERATOR = BLOCKS.register(
            "pasture_generator",
            () -> new PastureGeneratorBlock(machineProps(MapColor.WOOL))
    );

    /** 牧场货物检测器 */
    public static final Supplier<PastureDetectorBlock> PASTURE_DETECTOR = BLOCKS.register(
            "pasture_detector",
            () -> new PastureDetectorBlock(machineProps(MapColor.WOOL))
    );

    // === 冶金厂专属方块 ===

    /** 冶金厂货运站：构造时即为 METALLURGY 类型 */
    public static final Supplier<MetallurgyStationBlock> METALLURGY_STATION = BLOCKS.register(
            "metallurgy_station",
            () -> new MetallurgyStationBlock(machineProps(MapColor.METAL))
    );

    /** 冶金厂货物生成器 */
    public static final Supplier<MetallurgyGeneratorBlock> METALLURGY_GENERATOR = BLOCKS.register(
            "metallurgy_generator",
            () -> new MetallurgyGeneratorBlock(machineProps(MapColor.METAL))
    );

    /** 冶金厂货物检测器 */
    public static final Supplier<MetallurgyDetectorBlock> METALLURGY_DETECTOR = BLOCKS.register(
            "metallurgy_detector",
            () -> new MetallurgyDetectorBlock(machineProps(MapColor.METAL))
    );

    /** 货箱连接器：唯一需要可装配（可破坏）的方块；启用后载具靠近货箱即对接焊接 */
    public static final Supplier<CargoConnectorBlock> CARGO_CONNECTOR = BLOCKS.register(
            "cargo_connector",
            () -> new CargoConnectorBlock(connectorProps(MapColor.METAL).noOcclusion())
    );
}
