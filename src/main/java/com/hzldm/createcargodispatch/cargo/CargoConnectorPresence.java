package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.block.CargoConnectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * 货箱连接器存在性检测。
 *
 * <p>场景：连接器装在载具上（随载具物理化移动）。检测红框世界范围内，
 * 是否存在某载具 SubLevel 中的连接器方块，并返回其定位信息。
 *
 * <p>坐标转换统一走 {@link SableCoords}（内部方块坐标 → 世界坐标）。
 * plot bbox 缺失时主动调 {@code updateBoundingBox} 重建（Sable 仅在
 * addChunkHolder 时更新，存档恢复后可能缺失），避免检测被永久卡死。
 */
public final class CargoConnectorPresence {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("CargoDispatch-ConnectorPresence");

    private CargoConnectorPresence() {}

    /** 检测 center 周围红框内是否含连接器 */
    public static boolean isConnectorInRange(ServerLevel level, BlockPos center) {
        return findConnectorTarget(level, center) != null;
    }

    /**
     * 查找红框内第一个连接器目标（载具 + 连接器内部坐标）；无则 null。
     *
     * @param center 红框中心（调用方传入 ScanVolumes.centerAbove 的结果）
     */
    public static ConnectorTarget findConnectorTarget(ServerLevel level, BlockPos center) {
        if (!SubLevelScanner.isAvailable()) {
            return null;
        }
        int rx = SubLevelScanner.rangeXz();
        int ry = SubLevelScanner.rangeY();
        List<Object> nearby = SubLevelScanner.getNearbySubLevels(level, center, rx, ry);
        for (Object subLevel : nearby) {
            ConnectorTarget target = subLevelFindConnector(subLevel, center, rx, ry);
            if (target != null) return target;
        }
        return null;
    }

    private static ConnectorTarget subLevelFindConnector(Object subLevel, BlockPos center, int rx, int ry) {
        try {
            UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
            Level inner = SubLevelScanner.getSubLevelInternalLevel(subLevel);
            if (inner == null) {
                return null;
            }

            Object plot = SubLevelScanner.getPlot(subLevel);
            int[] pb = SubLevelScanner.plotBBoxMinMax(SubLevelScanner.getPlotBBox(plot));
            if (pb[3] < 0) {
                // 兜底重建：存档恢复等路径后 localBounds 缺失，强制按已加载 chunk 聚合后再读一次
                SubLevelScanner.refreshPlotBounds(plot);
                pb = SubLevelScanner.plotBBoxMinMax(SubLevelScanner.getPlotBBox(plot));
                if (pb[3] < 0) {
                    LOGGER.warn("SubLevel {} plot bbox 重建后仍无效，连接器检测跳过", uuid);
                    return null;
                }
            }

            // 红框世界边界
            double minX = center.getX() - rx, maxX = center.getX() + rx + 1;
            double minY = center.getY() - ry, maxY = center.getY() + ry + 1;
            double minZ = center.getZ() - rx, maxZ = center.getZ() + rx + 1;

            for (int x = pb[0]; x <= pb[3]; x++) {
                for (int y = pb[1]; y <= pb[4]; y++) {
                    for (int z = pb[2]; z <= pb[5]; z++) {
                        if (!(inner.getBlockState(new BlockPos(x, y, z)).getBlock()
                                instanceof CargoConnectorBlock)) {
                            continue;
                        }
                        // 连接器方块中心：内部坐标 → 世界坐标
                        Vector3d world = SableCoords.innerToWorld(subLevel, x + 0.5, y + 0.5, z + 0.5);
                        if (world == null) {
                            continue;
                        }
                        boolean in = world.x + 1e-4 >= minX && world.x - 1e-4 <= maxX
                                && world.y + 1e-4 >= minY && world.y - 1e-4 <= maxY
                                && world.z + 1e-4 >= minZ && world.z - 1e-4 <= maxZ;
                        if (!in) continue;
                        // 红石闸门：连接器必须已被红石信号启用、且未处于 attach 吸附态。
                        // 是否已有接单货箱由放置阶段的空间检查兜底（非空即失败）。
                        BlockPos connectorPos = new BlockPos(x, y, z);
                        if (inner.getBlockEntity(connectorPos) instanceof
                                com.hzldm.createcargodispatch.blockentity.CargoConnectorBlockEntity connectorBE
                                && connectorBE.isEnabled() && !connectorBE.isAttached()) {
                            return new ConnectorTarget(subLevel, connectorPos, uuid);
                        }
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            LOGGER.warn("连接器检测异常：{}", t.toString());
            return null;
        }
    }
}
