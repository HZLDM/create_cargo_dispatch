package com.hzldm.createcargodispatch.blockentity;

/**
 * 货物生成器出货模式。
 *
 * <ul>
 *   <li>{@link #GROUND}：接单后货箱直接生成在生成器上方（默认，原行为）</li>
 *   <li>{@link #CONNECTOR}：仅当红色检测范围内存在货箱连接器（载具）时才生成；
 *       接单时无连接器的订单进入临时等待列表，连接器出现后补生成</li>
 * </ul>
 */
public enum GeneratorSpawnMode {
    GROUND,
    CONNECTOR;

    /** 容错解析（非法值回退 GROUND） */
    public static GeneratorSpawnMode byName(String name) {
        if (name != null) {
            for (GeneratorSpawnMode m : values()) {
                if (m.name().equals(name)) return m;
            }
        }
        return GROUND;
    }
}
