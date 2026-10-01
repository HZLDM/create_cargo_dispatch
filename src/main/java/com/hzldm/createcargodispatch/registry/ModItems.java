package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.item.CleanBlockItem;
import com.hzldm.createcargodispatch.item.SelectableStationItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 物品注册类
 *
 * 为什么使用 CleanBlockItem 而非标准 BlockItem？
 *  标准 BlockItem 会自动附加 BLOCK_ENTITY_DATA DataComponent（BlockEntity 的默认 NBT 快照）
 *  到创造栏的 ItemStack 上。Sable、Jade 等外部模组读取此组件时会把 raw bytes 当成字符串渲染，
 *  当 NBT 中存在编码污染字节时，就会在物品 tooltip 下方出现乱码（mojibake）。
 *  使用 CleanBlockItem 在 getDefaultInstance() 阶段主动移除该组件，保证创造栏显示纯净。
 */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            net.minecraft.core.registries.Registries.ITEM, CreateCargoDispatch.MODID);

    public static final Supplier<Item> CARGO = registerBlockItem("cargo", ModBlocks.CARGO);

    // 通用（可转换）货运方块：创造栏只暴露这 3 个，潜行+右键选择具体类型后由服务端转换为专属物品
    public static final Supplier<Item> CARGO_DETECTOR = registerSelectableItem("cargo_detector", ModBlocks.CARGO_DETECTOR, SelectableStationItem.ItemKind.DETECTOR);
    public static final Supplier<Item> CARGO_GENERATOR = registerSelectableItem("cargo_generator", ModBlocks.CARGO_GENERATOR, SelectableStationItem.ItemKind.GENERATOR);
    public static final Supplier<Item> CARGO_STATION = registerSelectableItem("cargo_station", ModBlocks.CARGO_STATION, SelectableStationItem.ItemKind.STATION);

    // 专属货箱方块物品
    public static final Supplier<Item> LUMBER_YARD_CARGO = registerBlockItem("lumber_yard_cargo", ModBlocks.LUMBER_YARD_CARGO);
    public static final Supplier<Item> MINE_CARGO = registerBlockItem("mine_cargo", ModBlocks.MINE_CARGO);
    public static final Supplier<Item> FARM_CARGO = registerBlockItem("farm_cargo", ModBlocks.FARM_CARGO);
    public static final Supplier<Item> PASTURE_CARGO = registerBlockItem("pasture_cargo", ModBlocks.PASTURE_CARGO);
    public static final Supplier<Item> METALLURGY_CARGO = registerBlockItem("metallurgy_cargo", ModBlocks.METALLURGY_CARGO);

    // 伐木场专属方块物品
    public static final Supplier<Item> LUMBER_YARD_STATION = registerBlockItem("lumber_yard_station", ModBlocks.LUMBER_YARD_STATION);
    public static final Supplier<Item> LUMBER_YARD_GENERATOR = registerBlockItem("lumber_yard_generator", ModBlocks.LUMBER_YARD_GENERATOR);
    public static final Supplier<Item> LUMBER_YARD_DETECTOR = registerBlockItem("lumber_yard_detector", ModBlocks.LUMBER_YARD_DETECTOR);

    // 矿山专属方块物品
    public static final Supplier<Item> MINE_STATION = registerBlockItem("mine_station", ModBlocks.MINE_STATION);
    public static final Supplier<Item> MINE_GENERATOR = registerBlockItem("mine_generator", ModBlocks.MINE_GENERATOR);
    public static final Supplier<Item> MINE_DETECTOR = registerBlockItem("mine_detector", ModBlocks.MINE_DETECTOR);

    // 农场专属方块物品
    public static final Supplier<Item> FARM_STATION = registerBlockItem("farm_station", ModBlocks.FARM_STATION);
    public static final Supplier<Item> FARM_GENERATOR = registerBlockItem("farm_generator", ModBlocks.FARM_GENERATOR);
    public static final Supplier<Item> FARM_DETECTOR = registerBlockItem("farm_detector", ModBlocks.FARM_DETECTOR);

    // 牧场专属方块物品
    public static final Supplier<Item> PASTURE_STATION = registerBlockItem("pasture_station", ModBlocks.PASTURE_STATION);
    public static final Supplier<Item> PASTURE_GENERATOR = registerBlockItem("pasture_generator", ModBlocks.PASTURE_GENERATOR);
    public static final Supplier<Item> PASTURE_DETECTOR = registerBlockItem("pasture_detector", ModBlocks.PASTURE_DETECTOR);

    // 冶金厂专属方块物品
    public static final Supplier<Item> METALLURGY_STATION = registerBlockItem("metallurgy_station", ModBlocks.METALLURGY_STATION);
    public static final Supplier<Item> METALLURGY_GENERATOR = registerBlockItem("metallurgy_generator", ModBlocks.METALLURGY_GENERATOR);
    public static final Supplier<Item> METALLURGY_DETECTOR = registerBlockItem("metallurgy_detector", ModBlocks.METALLURGY_DETECTOR);

    // 货箱连接器
    public static final Supplier<Item> CARGO_CONNECTOR = registerBlockItem("cargo_connector", ModBlocks.CARGO_CONNECTOR);

    // 调试货箱（不可放置的纯物品，右键配置后生成可放置货箱）
    public static final Supplier<Item> DEBUG_CARGO = ITEMS.register("debug_cargo",
            () -> new com.hzldm.createcargodispatch.item.DebugCargoItem(new Item.Properties()));

    private static Supplier<Item> registerBlockItem(String name, Supplier<? extends Block> block) {
        // 使用 CleanBlockItem 去除创造栏 ItemStack 上的 BLOCK_ENTITY_DATA，阻断外部模组追加乱码 tooltip
        return ITEMS.register(name, () -> new CleanBlockItem(block.get(), new Item.Properties()));
    }

    /** 注册「可转换通用物品」：潜行+右键打开类型选择页，选择后由服务端转换为专属类型物品 */
    private static Supplier<Item> registerSelectableItem(String name, Supplier<? extends Block> block,
                                                          SelectableStationItem.ItemKind kind) {
        return ITEMS.register(name, () -> new SelectableStationItem(block.get(), new Item.Properties(), kind));
    }
}
