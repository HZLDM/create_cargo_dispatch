package com.hzldm.createcargodispatch.item;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.registry.ModItems;
import net.minecraft.world.item.Item;

/**
 * 货运站/生成器/检测器 通用物品 → 专属类型物品 的转换映射（服务端/客户端共用）
 *
 * 原理：
 *  - 创造栏只暴露 3 个「通用」物品（cargo_station / cargo_generator / cargo_detector），
 *    玩家潜行+右键打开选择页选定类型后，由服务端把主手物品替换为对应的专属 BlockItem
 *    （如 farm_station），放置后产生专属 BlockEntity（构造时已写死类型）。
 *  - 转换映射集中在此，避免 ModPayloads（服务端）与 StationTypeSelectScreen（客户端）
 *    各自维护一份 switch，防止两侧映射漂移。
 */
public final class StationItemConverter {

    private StationItemConverter() {}

    /** 按「通用物品类别 + 目标类型」解析最终物品 */
    public static Item resolve(SelectableStationItem.ItemKind kind, StationType type) {
        if (type == null) type = StationType.GENERIC;
        return switch (kind) {
            case STATION -> stationFor(type);
            case GENERATOR -> generatorFor(type);
            case DETECTOR -> detectorFor(type);
        };
    }

    /** 货运站：类型 → 对应专属站物品（GENERIC 返回通用站） */
    public static Item stationFor(StationType type) {
        return switch (type) {
            case LUMBER_YARD -> ModItems.LUMBER_YARD_STATION.get();
            case MINE -> ModItems.MINE_STATION.get();
            case FARM -> ModItems.FARM_STATION.get();
            case PASTURE -> ModItems.PASTURE_STATION.get();
            case METALLURGY -> ModItems.METALLURGY_STATION.get();
            case GENERIC -> ModItems.CARGO_STATION.get();
        };
    }

    /** 货箱生成器：类型 → 对应专属生成器物品（GENERIC 返回通用生成器） */
    public static Item generatorFor(StationType type) {
        return switch (type) {
            case LUMBER_YARD -> ModItems.LUMBER_YARD_GENERATOR.get();
            case MINE -> ModItems.MINE_GENERATOR.get();
            case FARM -> ModItems.FARM_GENERATOR.get();
            case PASTURE -> ModItems.PASTURE_GENERATOR.get();
            case METALLURGY -> ModItems.METALLURGY_GENERATOR.get();
            case GENERIC -> ModItems.CARGO_GENERATOR.get();
        };
    }

    /** 货物检测器：类型 → 对应专属检测器物品（GENERIC 返回通用检测器） */
    public static Item detectorFor(StationType type) {
        return switch (type) {
            case LUMBER_YARD -> ModItems.LUMBER_YARD_DETECTOR.get();
            case MINE -> ModItems.MINE_DETECTOR.get();
            case FARM -> ModItems.FARM_DETECTOR.get();
            case PASTURE -> ModItems.PASTURE_DETECTOR.get();
            case METALLURGY -> ModItems.METALLURGY_DETECTOR.get();
            case GENERIC -> ModItems.CARGO_DETECTOR.get();
        };
    }
}
