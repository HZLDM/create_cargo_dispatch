package com.hzldm.createcargodispatch.cargo;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 货运站类型
 *
 * 原理：
 *  - 每种类型对应特定的货物候选池
 *  - 伐木场只生成原木类订单，矿场只生成矿石类订单
 *  - 通过 byId 支持序列化与配置扩展
 */
public enum StationType {

    /** 通用（所有货物） */
    GENERIC("generic", "create_cargo_dispatch.station_type.generic"),

    /** 伐木场（原木类） */
    LUMBER_YARD("lumber_yard", "create_cargo_dispatch.station_type.lumber_yard"),

    /** 矿场（矿石类） */
    MINE("mine", "create_cargo_dispatch.station_type.mine"),

    /** 农场（农作物类） */
    FARM("farm", "create_cargo_dispatch.station_type.farm"),

    /** 牧场（畜牧产品类） */
    PASTURE("pasture", "create_cargo_dispatch.station_type.pasture"),

    /** 冶金厂（金属锭/合金类） */
    METALLURGY("metallurgy", "create_cargo_dispatch.station_type.metallurgy");

    private final String id;
    private final String translationKey;

    StationType(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public String getId() {
        return id;
    }

    /** 本地化翻译键，用于 UI 显示 */
    public String getTranslationKey() {
        return translationKey;
    }

    /**
     * 短名本地化翻译键——用于「订单起点→终点」这类紧凑显示
     * 例如 station_type.pasture = "牧场货物"，而 station_short.pasture = "牧场"
     * 为什么要单独一个短名：UI 上一行已经有"生羊肉x310 96"、"→(x,y,z)"等元素，如果类型还带"货物"二字，会过长
     */
    public String getShortTranslationKey() {
        return "create_cargo_dispatch.station_short." + id;
    }

    /** 获取英文 ID，用于 Jade HUD、配置序列化等场景（已在上方定义） */

    /** 通过 ID 查找类型，未知 ID 返回 GENERIC */
    public static StationType byId(String id) {
        if (id == null || id.isEmpty()) return GENERIC;
        for (StationType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return GENERIC;
    }

    /**
     * 获取该类型对应的货物候选池
     *
     * 原理：
     *  - LUMBER_YARD 只返回各种原木
     *  - MINE 返回原矿石（非矿锭，更符合采矿场景）
     *  - FARM 返回农作物
     *  - GENERIC 返回所有类型
     */
    public List<Item> getCargoPool() {
        return switch (this) {
            case LUMBER_YARD -> List.of(
                    Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG,
                    Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
                    Items.MANGROVE_LOG, Items.CHERRY_LOG
            );
            case MINE -> List.of(
                    Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE,
                    Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE,
                    Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE,
                    Items.EMERALD_ORE, Items.DEEPSLATE_EMERALD_ORE,
                    Items.REDSTONE_ORE, Items.DEEPSLATE_REDSTONE_ORE,
                    Items.COAL_ORE, Items.DEEPSLATE_COAL_ORE,
                    Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE,
                    Items.LAPIS_ORE, Items.DEEPSLATE_LAPIS_ORE
            );
            case FARM -> List.of(
                    Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
                    Items.PUMPKIN, Items.MELON_SLICE, Items.APPLE
            );
            case PASTURE -> List.of(
                    Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON,
                    Items.WHITE_WOOL, Items.LEATHER, Items.EGG, Items.FEATHER
            );
            case METALLURGY -> List.of(
                    Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT,
                    Items.NETHERITE_INGOT, Items.BRICK, Items.AMETHYST_SHARD
            );
            case GENERIC -> List.of(
                    Items.IRON_ORE, Items.GOLD_ORE, Items.DIAMOND_ORE, Items.EMERALD_ORE,
                    Items.REDSTONE_ORE, Items.COAL_ORE, Items.COPPER_ORE,
                    Items.OAK_LOG, Items.STONE
            );
        };
    }

    /** 所有可能出现的货物（五种类型货物池去重联合，调试页可任意选择），惰性缓存 */
    private static List<Item> allCargoPool;

    public static List<Item> getAllCargoPool() {
        if (allCargoPool == null) {
            java.util.LinkedHashSet<Item> union = new java.util.LinkedHashSet<>();
            for (StationType type : values()) {
                if (type == GENERIC) continue; // GENERIC 是混合子集，避免重复
                union.addAll(type.getCargoPool());
            }
            allCargoPool = List.copyOf(union);
        }
        return allCargoPool;
    }
}
