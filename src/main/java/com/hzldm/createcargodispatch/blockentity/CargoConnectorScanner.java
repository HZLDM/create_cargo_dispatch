package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * 连接器扫描器：
 * <ul>
 *   <li>在给定扫描范围内查找邻近的 CCD 货箱 SubLevel</li>
 *   <li>根据货箱几何 bounding box 计算「对齐中心 + 贴合顶部」所需的整体偏移量</li>
 * </ul>
 *
 * <p>SRP：把扫描/几何计算从业务策略里抽出，便于对对齐算法做单测，也便于后续扩展
 * （例如改为「顶部居中」「底部居左」「吸附到连接器侧面」等多种模式）。
 */
public final class CargoConnectorScanner {

    /** 吸附时扫描附近 SubLevel 的水平范围（方块） */
    public static final int SCAN_RANGE_XZ = 8;
    /** 吸附时扫描附近 SubLevel 的垂直范围（方块） */
    public static final int SCAN_RANGE_Y  = 6;
    private CargoConnectorScanner() {}

    // ========================================================================
    // 扫描
    // ========================================================================

    /**
     * 在连接器附近寻找物理化的货箱 SubLevel（排除连接器自身所在 SubLevel）。
     *
     * <p>优先匹配 CargoManager 已知的货箱 SubLevel，保证 CCD 内部状态一致；
     * 若没有任何 CCD 货箱，则兜底返回第一个「非自身」的 SubLevel（允许吸附外部 SubLevel）。
     */
    @Nullable
    public static Object findNearbyCargoSubLevel(ServerLevel sl, BlockPos center) {
        List<Object> nearby = SubLevelScanner.getNearbySubLevels(sl, center, SCAN_RANGE_XZ, SCAN_RANGE_Y);
        if (nearby.isEmpty()) return null;

        Object myself = SubLevelScanner.findSubLevelContainingGlobalPos(sl, center);
        UUID myUuid = (myself != null) ? SubLevelScanner.getSubLevelUuid(myself) : null;

        // ① 优先：CCD 系统已知的货箱 SubLevel（CargoManager 注册过）
        for (Object subLevel : nearby) {
            UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
            if (uuid == null) continue;
            if (myUuid != null && myUuid.equals(uuid)) continue;
            if (CargoManager.getStartPosBySubLevel(uuid) != null) return subLevel;
        }
        // ② 兜底：返回第一个非自身 SubLevel（即便 CargoManager 没注册也能尝试吸附）
        for (Object subLevel : nearby) {
            UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
            if (myUuid != null && myUuid.equals(uuid)) continue;
            return subLevel;
        }
        return null;
    }

    // ========================================================================
    // 几何对齐
    // ========================================================================

    /**
     * 货箱在自身局部坐标系下的底部中心点（3×3 截面居中、高度取最低）。
     * 无方块数据时退化为锚点处 (0.5,0,0.5)。
     */
    public static Vector3d bottomCenter(@Nullable List<BlockPos> cargoBlocks) {
        if (cargoBlocks == null || cargoBlocks.isEmpty()) {
            return new Vector3d(0.5, 0.0, 0.5);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cargoBlocks) {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;
        }
        return new Vector3d((minX + maxX) / 2.0 + 0.5, minY, (minZ + maxZ) / 2.0 + 0.5);
    }

    /**
     * 货箱底部中心在<b>物理局部坐标系</b>的位置（相对主方块 anchor）。
     * 固定约束锚点/偏移必须用局部坐标，不能用主世界绝对坐标。
     *
     * @param anchor 货箱主方块位置（CargoManager.getStartPosBySubLevel）
     */
    public static Vector3d bottomCenterLocal(@Nullable List<BlockPos> cargoBlocks, BlockPos anchor) {
        if (anchor == null) return new Vector3d(0.5, 0.0, 0.5);
        return bottomCenter(cargoBlocks).sub(anchor.getX(), anchor.getY(), anchor.getZ());
    }
}
