package com.hzldm.createcargodispatch.integration.xaero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Xaero's Minimap 反射封装
 *
 * 原理：
 *  - Xaero 无官方 API（ARR 许可），通过反射调用内部类
 *  - Xaero 不存在时静默失败，不影响模组运行
 *  - API 漂移只警告一次，避免日志刷屏
 *  - MethodHandle 缓存减少反射开销
 *
 * 调用链：
 *  BuiltInHudModules.MINIMAP → getCurrentSession() → getWorldManager()
 *  → getCurrentWorld() → getCurrentWaypointSet() → add(Waypoint, boolean)
 */
public final class XaeroReflect {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Xaero");
    private static volatile Boolean xaeroPresent = null;
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    // 反射缓存
    private static volatile MethodHandle MH_GET_CURRENT_WORLD;
    private static volatile MethodHandle MH_GET_WAYPOINT_SET;

    private XaeroReflect() {
    }

    /** 检测 Xaero 是否存在（缓存结果） */
    public static boolean isXaeroPresent() {
        Boolean cached = xaeroPresent;
        if (cached != null) {
            return cached;
        }
        try {
            Class.forName("xaero.hud.minimap.BuiltInHudModules");
            xaeroPresent = true;
        } catch (Throwable t) {
            xaeroPresent = false;
        }
        return xaeroPresent;
    }

    /** 获取当前 MinimapWorld（玩家所在维度），失败返回 null */
    public static Object getCurrentMinimapWorld() {
        if (!isXaeroPresent()) {
            return null;
        }
        try {
            Class<?> builtInCls = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = builtInCls.getField("MINIMAP");
            Object minimapModule = minimapField.get(null);
            if (minimapModule == null) {
                return null;
            }
            Class<?> hudModuleCls = Class.forName("xaero.hud.module.HudModule");
            Object session = hudModuleCls.getMethod("getCurrentSession").invoke(minimapModule);
            if (session == null) {
                return null;
            }
            Class<?> sessionCls = Class.forName("xaero.hud.minimap.module.MinimapSession");
            Object worldManager = sessionCls.getMethod("getWorldManager").invoke(session);
            if (worldManager == null) {
                return null;
            }
            Class<?> wmCls = Class.forName("xaero.hud.minimap.world.MinimapWorldManager");
            return wmCls.getMethod("getCurrentWorld").invoke(worldManager);
        } catch (Throwable t) {
            warnOnce("getCurrentMinimapWorld", t);
            return null;
        }
    }

    /** 从 MinimapWorld 取当前 WaypointSet */
    public static Object getCurrentWaypointSet(Object minimapWorld) {
        if (minimapWorld == null) {
            return null;
        }
        try {
            Class<?> worldCls = Class.forName("xaero.hud.minimap.world.MinimapWorld");
            return worldCls.getMethod("getCurrentWaypointSet").invoke(minimapWorld);
        } catch (Throwable t) {
            warnOnce("getCurrentWaypointSet", t);
            return null;
        }
    }

    /**
     * 构造 Waypoint
     * 构造器：(int x, int y, int z, String name, String initials,
     *         WaypointColor color, WaypointPurpose purpose,
     *         boolean temporary, boolean yIncluded)
     */
    public static Object newWaypoint(int x, int y, int z, String name, String initials) {
        if (!isXaeroPresent()) {
            return null;
        }
        try {
            Class<?> colorCls = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Object color = colorCls.getField("PURPLE").get(null);
            Class<?> purposeCls = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            Object purpose = purposeCls.getField("NORMAL").get(null);

            Class<?> wpCls = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Constructor<?> ctor = wpCls.getConstructor(
                    int.class, int.class, int.class,
                    String.class, String.class,
                    colorCls, purposeCls,
                    boolean.class, boolean.class);
            return ctor.newInstance(x, y, z, name, initials, color, purpose, false, true);
        } catch (Throwable t) {
            warnOnce("newWaypoint", t);
            return null;
        }
    }

    /** 添加路径点：WaypointSet.add(Waypoint, boolean) */
    public static boolean addWaypoint(Object waypointSet, Object waypoint) {
        if (waypointSet == null || waypoint == null) {
            return false;
        }
        try {
            Class<?> setCls = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
            Class<?> wpCls = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Method add = setCls.getMethod("add", wpCls, boolean.class);
            add.invoke(waypointSet, waypoint, false);
            return true;
        } catch (Throwable t) {
            warnOnce("addWaypoint", t);
            return false;
        }
    }

    /**
     * 按名称删除路径点（含模糊匹配兜底）
     * 原理：
     *  - 第 1 层：精确名称匹配（兼容性最好，不走额外逻辑）
     *  - 第 2 层：如果精确匹配失败，尝试提取 name 中「#」之后的 orderId 片段
     *    → 提取 orderId 短号（前 8 位）
     *    → 遍历所有 wp，只要 wp.getName() 里包含该短号即视为匹配（覆盖玩家改名/不同前缀）
     *  - 命中任何一个路径点即保存并返回 true
     *
     * 用于订单完成时清理对应的路径点（存在「货运订单 #」「订单起点#」两套命名体系）
     *
     * @param waypointSet 当前 WaypointSet
     * @param name        要删除的路径点名称（精确名）
     * @return 删除成功返回 true
     */
    public static boolean removeWaypointByName(Object waypointSet, String name) {
        if (waypointSet == null || name == null) {
            return false;
        }
        try {
            Class<?> setCls = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
            Class<?> wpCls = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            // 获取路径点列表
            Method getWaypoints = setCls.getMethod("getWaypoints");
            Object iterable = getWaypoints.invoke(waypointSet);
            if (!(iterable instanceof Iterable<?> waypoints)) {
                return false;
            }

            // ========== 1) 精确匹配 ==========
            Object toRemove = null;
            for (Object wp : waypoints) {
                String wpName = getWaypointName(wp, wpCls);
                if (wpName == null) continue;
                if (name.equals(wpName)) {
                    toRemove = wp;
                    break;
                }
            }

            // ========== 2) 模糊匹配兜底（提取orderId短号包含匹配） ==========
            if (toRemove == null) {
                // 从传入 name 里提取 "#" 后的部分作为 orderId 候选
                String orderIdCandidate = null;
                int hashIdx = name.lastIndexOf('#');
                if (hashIdx >= 0 && hashIdx + 1 < name.length()) {
                    orderIdCandidate = name.substring(hashIdx + 1);
                } else if (name.length() >= 4) {
                    // 没有 # 的纯字符串（如直接传 orderId），把整个 name 视为候选
                    orderIdCandidate = name;
                }
                if (orderIdCandidate != null && !orderIdCandidate.isEmpty()) {
                    // 短号（前 8 位，客户端通常只显示前 8 位，玩家可能手动改名为短号版）
                    String shortCandidate = orderIdCandidate.length() > 8
                            ? orderIdCandidate.substring(0, 8)
                            : orderIdCandidate;
                    for (Object wp : waypoints) {
                        String wpName = getWaypointName(wp, wpCls);
                        if (wpName == null || wpName.isEmpty()) continue;
                        // 三种包含关系任一命中就匹配：完整orderId / 短号
                        if (wpName.contains(orderIdCandidate) || wpName.contains(shortCandidate)) {
                            toRemove = wp;
                            break;
                        }
                    }
                }
            }

            if (toRemove == null) {
                return false;
            }
            // 调用 remove(Waypoint)
            Method remove = setCls.getMethod("remove", wpCls);
            remove.invoke(waypointSet, toRemove);
            return true;
        } catch (Throwable t) {
            warnOnce("removeWaypointByName", t);
            return false;
        }
    }

    /** 内部辅助：统一安全获取 Waypoint 的 name 字符串（优先 getName()，失败用字段） */
    @org.jetbrains.annotations.Nullable
    private static String getWaypointName(Object wp, Class<?> wpCls) {
        if (wp == null) return null;
        try {
            Method getName = wpCls.getMethod("getName");
            return (String) getName.invoke(wp);
        } catch (NoSuchMethodException ignored) {
            try {
                Field nameField = wpCls.getField("name");
                return (String) nameField.get(wp);
            } catch (NoSuchFieldException ignored2) {
                return null;
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 保存当前世界的路径点到磁盘
     * 原理：
     *  - 通过反射访问 MinimapSession 的私有字段 worldManagerIO
     *  - 调用 saveWorld(MinimapWorld) 持久化路径点
     *  - Xaero 默认只在退出世界时保存，添加路径点后需显式调用
     *
     * @param minimapWorld 要保存的 MinimapWorld（通常为 getCurrentMinimapWorld() 返回值）
     * @return 保存成功返回 true
     */
    public static boolean saveWorld(Object minimapWorld) {
        if (!isXaeroPresent() || minimapWorld == null) {
            return false;
        }
        try {
            // 获取 MinimapSession
            Class<?> builtInCls = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimapField = builtInCls.getField("MINIMAP");
            Object minimapModule = minimapField.get(null);
            if (minimapModule == null) return false;

            Class<?> hudModuleCls = Class.forName("xaero.hud.module.HudModule");
            Object session = hudModuleCls.getMethod("getCurrentSession").invoke(minimapModule);
            if (session == null) return false;

            // 反射访问私有字段 worldManagerIO
            Class<?> sessionCls = Class.forName("xaero.hud.minimap.module.MinimapSession");
            Field ioField = sessionCls.getDeclaredField("worldManagerIO");
            ioField.setAccessible(true);
            Object io = ioField.get(session);
            if (io == null) return false;

            // 调用 saveWorld(MinimapWorld)
            Class<?> ioCls = Class.forName("xaero.hud.minimap.world.io.MinimapWorldManagerIO");
            Class<?> worldCls = Class.forName("xaero.hud.minimap.world.MinimapWorld");
            Method saveMethod = ioCls.getMethod("saveWorld", worldCls);
            saveMethod.invoke(io, minimapWorld);
            LOGGER.info("[CargoDispatch] Xaero 路径点已保存到磁盘");
            return true;
        } catch (Throwable t) {
            warnOnce("saveWorld", t);
            return false;
        }
    }

    private static void warnOnce(String operation, Throwable cause) {
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn("Xaero's Minimap API 漂移: {} ({})", operation, cause.toString());
        }
    }
}
