package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * 红框内检测到的货箱连接器目标。
 *
 * <p>相比只返回 boolean，结构化结果携带「载具 SubLevel 实例 + 连接器内部坐标」，
 * 接单流程可据此直接执行对齐焊接，无需再次扫描。
 *
 * @param vehicle      连接器所在载具 SubLevel
 * @param connectorPos 连接器在载具内部世界的方块坐标
 * @param vehicleUuid  载具 UUID
 */
public record ConnectorTarget(Object vehicle, BlockPos connectorPos, UUID vehicleUuid) {
}
