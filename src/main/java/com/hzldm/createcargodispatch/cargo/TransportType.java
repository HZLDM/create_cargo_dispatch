package com.hzldm.createcargodispatch.cargo;

/**
 * 运输类型枚举
 *
 * 原理：
 *  - 区分三种运输场景：普通货品配送、太空运、潜水艇运
 *  - 不同类型对应不同的多方块结构形态和路径生成规则
 */
public enum TransportType {
    /** 普通货品配送：同维度不同货运站之间 */
    LAND("land"),
    /** 太空运：主世界到希茉涅或反向 */
    SPACE("space"),
    /** 潜水艇运：希茉涅水下 */
    SUBMARINE("submarine");

    private final String id;

    TransportType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getTranslationKey() {
        return "create_cargo_dispatch.cargo.transport." + id;
    }

    public static TransportType byId(String id) {
        for (TransportType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return LAND;
    }
}
