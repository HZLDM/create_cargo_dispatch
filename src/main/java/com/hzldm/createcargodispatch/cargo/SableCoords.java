package com.hzldm.createcargodispatch.cargo;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sable SubLevel 坐标桥接：内部世界坐标 ↔ 世界坐标。
 *
 * <h3>数学原理：两盒中心对应</h3>
 * 内部 plot bbox 与 SubLevel 世界包围盒描述同一组方块，旋转只改变方向、中心对应不变：
 * <pre>
 *   world(c) = Q × (c − plotCenter) + globalCenter
 *   inner(w) = Q⁻¹ × (w − globalCenter) + plotCenter
 * </pre>
 * 该方法对「已稳定存在的载具」可靠，是接单时连接器检测的长期验证路径，
 * 不依赖物理引擎 pose 是否完成当刻同步。
 *
 * <h3>需要物理当刻精确值的场景（切割/装配）</h3>
 * 请直接用 {@link SubLevelScanner#worldPoint}（基于 logicalPose，即时但要求 pose 已同步）。
 * 两条路径分离，避免 pose 未初始化影响静止载具检测。
 *
 * <p>注意：plot bbox 的 max 是<b>包含</b>的方块坐标，几何外边界 = max+1；
 * 世界 bbox（double）的 max 已是几何外边界。
 */
public final class SableCoords {

    /**
     * SubLevel → plot 中心缓存：plot bbox 装配后固定；
     * 弱键：SubLevel 被回收时缓存自动清除，杜绝内存泄漏。
     */
    private static final Map<Object, double[]> PLOT_CENTER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SableCoords() {}

    /** 内部坐标点 → 世界坐标；失败 null */
    public static Vector3d innerToWorld(Object subLevel, double x, double y, double z) {
        double[] centers = readCenters(subLevel);
        if (centers == null) return null;
        Quaterniond q = readOrientation(subLevel);
        Vector3d offset = q.transform(new Vector3d(x - centers[0], y - centers[1], z - centers[2]));
        return new Vector3d(centers[3] + offset.x, centers[4] + offset.y, centers[5] + offset.z);
    }

    /** 世界坐标点 → 内部坐标；失败 null */
    public static Vector3d worldToInner(Object subLevel, double x, double y, double z) {
        double[] c = readCenters(subLevel);
        if (c == null) return null;
        Quaterniond invQ = readOrientation(subLevel).conjugate();
        Vector3d offset = invQ.transform(new Vector3d(x - c[3], y - c[4], z - c[5]));
        return new Vector3d(c[0] + offset.x, c[1] + offset.y, c[2] + offset.z);
    }

    /**
     * 读取两盒中心：{plotCX,CY,CZ, globalCX,CY,CZ}。
     * plot 中心缓存（结构固定），世界中心实时读取。
     */
    private static double[] readCenters(Object subLevel) {
        try {
            double[] gb = SubLevelScanner.globalBBoxComponents(subLevel);
            if (gb == null) return null;
            double[] pc = PLOT_CENTER_CACHE.get(subLevel);
            if (pc == null) {
                Object plot = SubLevelScanner.getPlot(subLevel);
                int[] pb = SubLevelScanner.plotBBoxMinMax(SubLevelScanner.getPlotBBox(plot));
                if (pb[3] < 0) return null;
                pc = new double[]{
                        (pb[0] + pb[3] + 1) / 2.0,
                        (pb[1] + pb[4] + 1) / 2.0,
                        (pb[2] + pb[5] + 1) / 2.0
                };
                PLOT_CENTER_CACHE.put(subLevel, pc);
            }
            return new double[]{
                    pc[0], pc[1], pc[2],
                    (gb[0] + gb[3]) / 2.0,
                    (gb[1] + gb[4]) / 2.0,
                    (gb[2] + gb[5]) / 2.0
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private static Quaterniond readOrientation(Object subLevel) {
        return SubLevelScanner.readSubLevelOrientation(subLevel) instanceof Quaterniondc qd
                ? new Quaterniond(qd) : new Quaterniond();
    }
}
