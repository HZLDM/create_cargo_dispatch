package com.hzldm.createcargodispatch.integration.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xaero 路径点服务
 *
 * 原理：
 *  - 必须在客户端主线程调用（通过 Minecraft.getInstance().execute）
 *  - 添加路径点到玩家当前所在维度
 *  - Xaero 不存在时返回 false，调用方降级处理
 */
public final class XaeroWaypointService {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Xaero");

    private XaeroWaypointService() {
    }

    /**
     * 在玩家当前维度添加路径点
     *
     * @param pos      坐标
     * @param name     名称
     * @param initials 缩写（1-3 字符）
     * @return 添加成功返回 Waypoint 引用，失败返回 null
     */
    public static Object addWaypoint(BlockPos pos, String name, String initials) {
        if (!XaeroReflect.isXaeroPresent()) {
            LOGGER.debug("[CargoDispatch] Xaero 未安装，跳过路径点添加");
            return null;
        }
        Object world = XaeroReflect.getCurrentMinimapWorld();
        if (world == null) {
            return null;
        }
        Object set = XaeroReflect.getCurrentWaypointSet(world);
        if (set == null) {
            return null;
        }
        Object wp = XaeroReflect.newWaypoint(pos.getX(), pos.getY(), pos.getZ(), name, initials);
        if (wp == null) {
            return null;
        }
        if (XaeroReflect.addWaypoint(set, wp)) {
            LOGGER.debug("[CargoDispatch] 已添加路径点: {} @ {}", name, pos);
            // 显式保存到磁盘，避免退出游戏后路径点丢失
            XaeroReflect.saveWorld(world);
            return wp;
        }
        return null;
    }

    /** 必须在客户端主线程调用 */
    public static void addWaypointOnClient(BlockPos pos, String name, String initials) {
        Minecraft.getInstance().execute(() -> addWaypoint(pos, name, initials));
    }

    /**
     * 按名称删除路径点
     * 原理：
     *  - 获取当前维度的 WaypointSet
     *  - 遍历查找名称匹配的路径点并删除
     *  - 删除后显式保存到磁盘
     *
     * @param name 要删除的路径点名称
     * @return 删除成功返回 true
     */
    public static boolean removeWaypoint(String name) {
        if (!XaeroReflect.isXaeroPresent()) {
            return false;
        }
        Object world = XaeroReflect.getCurrentMinimapWorld();
        if (world == null) {
            return false;
        }
        Object set = XaeroReflect.getCurrentWaypointSet(world);
        if (set == null) {
            return false;
        }
        if (XaeroReflect.removeWaypointByName(set, name)) {
            LOGGER.debug("[CargoDispatch] 已删除路径点: {}", name);
            // 删除后保存到磁盘
            XaeroReflect.saveWorld(world);
            return true;
        }
        return false;
    }

    /** 必须在客户端主线程调用 */
    public static void removeWaypointOnClient(String name) {
        Minecraft.getInstance().execute(() -> removeWaypoint(name));
    }
}
