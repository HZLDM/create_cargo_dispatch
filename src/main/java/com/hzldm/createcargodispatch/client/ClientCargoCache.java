package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.integration.xaero.XaeroWaypointService;
import com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncLinkagesPayload;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncSubmitListPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端订单缓存
 *
 * 原理：
 *  - 缓存服务端同步过来的订单列表，供 UI 渲染
 *  - 接单成功后通过 addWaypoint 在客户端添加 Xaero 路径点
 *  - 使用 CopyOnWriteArrayList 保证遍历时并发安全
 *  - TRACKED_WAYPOINTS：保存我们添加的路径点的名称→坐标+维度映射，
 *    供 ClientTickWaypointWatcher 每 20 tick 做"到达后自动删除"的距离检查
 *
 * 注意：
 *  - 仅客户端调用，类加载时通过 Class.forName 触发客户端类，
 *    避免服务端引入客户端包
 */
public final class ClientCargoCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Client");

    /** 当前可接单订单列表 */
    private static final List<SyncOrdersPayload.OrderEntry> ORDERS = new CopyOnWriteArrayList<>();

    /** 玩家正在进行的订单列表 */
    private static final List<SyncActiveOrdersPayload.ActiveOrderEntry> ACTIVE_ORDERS = new CopyOnWriteArrayList<>();

    // ======================================================================
    // 背包「已连接站点 → 点击 📋 订单按钮」的远程查看页元信息（不缓存订单，列表实时从全局 ORDERS 过滤）
    // ======================================================================

    /** 远程查看页：当前查看的货运站类型 ID（如 "lumber_yard"），关闭页面时置空 */
    private static volatile String viewerStationType = "";
    /** 远程查看页：当前查看的货运站坐标（不可变）。null=未打开任何查看页 */
    private static volatile net.minecraft.core.BlockPos viewerStationPos = null;

    /** 已连接的站点列表 */
    private static final List<SyncLinkagesPayload.LinkageEntry> LINKAGES = new CopyOnWriteArrayList<>();

    /** 下次刷新的绝对游戏时间（由服务端同步），0 表示未知 */
    private static volatile long nextRefreshGameTime = 0L;

    /**
     * 跟踪我们自己添加的路径点（用于到达后自动删除）
     * 原理：
     *  - key：路径点名称（Xaero 中的 name，如 "伐木场订单起点 #123"）
     *  - value：TrackedWaypoint（坐标 + 维度 ResourceLocation）
     *  - ConcurrentHashMap：保证并发读写安全
     */
    public static final Map<String, TrackedWaypoint> TRACKED_WAYPOINTS = new ConcurrentHashMap<>();

    /** 路径点跟踪条目 */
    public record TrackedWaypoint(BlockPos pos, ResourceLocation dimension) {}

    // ======================================================================
    // 货运站「提交页面」客户端缓存
    // ======================================================================

    /** 当前打开的货运站（提交页面显示的站）位置，null=未打开提交页 */
    private static volatile BlockPos currentSubmitStationPos = null;

    /** 当前提交页面待提交货箱列表 */
    private static final List<SyncSubmitListPayload.SubmitEntry> SUBMIT_ENTRIES = new CopyOnWriteArrayList<>();

    /** 当前货运站自动提交开关状态（服务端同步） */
    private static volatile boolean currentAutoSubmit = false;

    /** 是否已收到过当前站的连接状态（首次同步前不显示"未连接"警告，避免闪烁误报） */
    private static volatile boolean linkStatusReceived = false;
    /** 当前货运站是否已连接同组货物生成器 */
    private static volatile boolean currentHasGenerator = true;
    /** 当前货运站是否已连接同组货物检测器 */
    private static volatile boolean currentHasDetector = true;

    /** 上次向服务端请求刷新提交列表的游戏时间（避免每 tick 都发包） */
    private static volatile long lastSubmitRequestGameTime = 0L;

    /**
     * 更新提交页面缓存（由 SyncSubmitListPayload handler 调用）
     * @param sx stationX
     * @param sy stationY
     * @param sz stationZ
     * @param entries 待提交货箱列表
     * @param autoSubmit 自动提交开关状态
     */
    public static void updateSubmitList(int sx, int sy, int sz,
                                        List<SyncSubmitListPayload.SubmitEntry> entries,
                                        boolean autoSubmit,
                                        boolean hasGenerator,
                                        boolean hasDetector) {
        currentSubmitStationPos = new BlockPos(sx, sy, sz);
        currentAutoSubmit = autoSubmit;
        linkStatusReceived = true;
        currentHasGenerator = hasGenerator;
        currentHasDetector = hasDetector;

        // 客户端侧最后一道去重防线（防止服务端多 detector 重复下发同一订单/同一货箱）
        java.util.Set<Object> seen = new java.util.HashSet<>();
        SUBMIT_ENTRIES.clear();
        for (SyncSubmitListPayload.SubmitEntry e : entries) {
            Object key;
            if (e.hasSubLevel() && e.subLevelUuid() != null) {
                key = e.subLevelUuid();                                              // SubLevel 货箱：按 UUID 去重
            } else if (e.controllerX() != 0 || e.controllerY() != 0 || e.controllerZ() != 0) {
                key = new BlockPos(e.controllerX(), e.controllerY(), e.controllerZ());  // 静态货箱：按主方块位置去重
            } else {
                BlockPos detPos = new BlockPos(e.detectorX(), e.detectorY(), e.detectorZ());
                key = e.orderId() + "|" + detPos;                                    // 兜底
            }
            if (seen.add(key)) {
                SUBMIT_ENTRIES.add(e);
            } else {
                LOGGER.debug("[CargoDispatch] 客户端提交列表去重：跳过重复条目 orderId={} key={}", e.orderId(), key);
            }
        }
    }

    /** 清空提交页缓存（关闭 UI 时调用） */
    public static void clearSubmitList() {
        currentSubmitStationPos = null;
        SUBMIT_ENTRIES.clear();
        currentAutoSubmit = false;
        lastSubmitRequestGameTime = 0L;
        linkStatusReceived = false;
        currentHasGenerator = true;
        currentHasDetector = true;
    }

    public static BlockPos getCurrentSubmitStationPos() { return currentSubmitStationPos; }
    public static List<SyncSubmitListPayload.SubmitEntry> getSubmitEntries() { return List.copyOf(SUBMIT_ENTRIES); }
    public static boolean isCurrentAutoSubmit() { return currentAutoSubmit; }
    public static boolean isLinkStatusReceived() { return linkStatusReceived; }
    public static boolean isCurrentHasGenerator() { return currentHasGenerator; }
    public static boolean isCurrentHasDetector() { return currentHasDetector; }
    public static long getLastSubmitRequestGameTime() { return lastSubmitRequestGameTime; }
    public static void setLastSubmitRequestGameTime(long t) { lastSubmitRequestGameTime = t; }

    private ClientCargoCache() {
    }

    /** 更新订单缓存 + 下次刷新时间 */
    public static void updateOrders(List<SyncOrdersPayload.OrderEntry> orders, long nextRefresh) {
        ORDERS.clear();
        ORDERS.addAll(orders);
        nextRefreshGameTime = nextRefresh;
    }

    /** 获取当前缓存的订单 */
    public static List<SyncOrdersPayload.OrderEntry> getOrders() {
        return List.copyOf(ORDERS);
    }

    /** 获取下次刷新的绝对游戏时间 */
    public static long getNextRefreshGameTime() {
        return nextRefreshGameTime;
    }

    // ======================================================================
    // 背包远程查看站订单：元信息 setter/getter（不缓存订单列表，实时读全局 ORDERS 过滤）
    // ======================================================================

    /**
     * 设置远程查看页「当前正在看哪个站」。
     * 注意：不复制订单列表、不做"刷新"语义。订单列表由 getOrdersForStation() 实时从全局 ORDERS 过滤得到，
     * 与 CargoGeneratorScreen 现场 UI 共享同一数据源、同一更新节奏、同一剩余时间计算。
     *
     * @param sx 站 X
     * @param sy 站 Y
     * @param sz 站 Z
     * @param typeId 站类型 ID（如 "farm"），可以为 null
     * @param orders 兼容旧签名用，已忽略（不再缓存）
     * @param nextRefresh 兼容旧签名用，已忽略（不再缓存）
     */
    public static void updateStationViewerOrders(int sx, int sy, int sz, String typeId,
                                                 List<SyncOrdersPayload.OrderEntry> orders,
                                                 long nextRefresh) {
        viewerStationPos = new net.minecraft.core.BlockPos(sx, sy, sz);
        viewerStationType = (typeId != null) ? typeId : "";
        // orders/nextRefresh 已忽略：不再在查看页层缓存订单，完全共享全局 ORDERS
    }

    /** 简化版 setter：仅设置站坐标/类型（不关心订单参数时用，语义更清晰） */
    public static void setViewerStation(int sx, int sy, int sz, String typeId) {
        viewerStationPos = new net.minecraft.core.BlockPos(sx, sy, sz);
        viewerStationType = (typeId != null) ? typeId : "";
    }

    /** 关闭查看页时清空元信息缓存（防止下次开屏闪旧坐标/旧类型） */
    public static void clearStationViewerOrders() {
        viewerStationType = "";
        viewerStationPos = null;
        // 不再有订单列表缓存要清空
    }

    /** 当前查看的货运站坐标（null=未打开） */
    public static net.minecraft.core.BlockPos getViewerStationPos() { return viewerStationPos; }
    /** 当前查看的货运站类型 ID（空=未打开） */
    public static String getViewerStationTypeId() { return viewerStationType; }

    /**
     * 「该站未接订单」实时视图 —— 直接从全局 ORDERS 按「同站匹配」过滤。
     * 匹配规则（与服务端 OrderManager.isOrderStartBelongsToStation 完全对称，保证两端过滤结果一致）：
     *   1) 类型相同（订单 sourceStationType == 期望类型）；stationTypeId 为空/"generic" 时跳过类型判断（兼容泛型站）
     *   2) 起点坐标精确相等 → 优先短路；否则按 XZ≤20 / Y≤8 立方范围命中（单站结构尺寸，绝不会错进相邻站）
     *
     * @param sx           查看站的X坐标（站本体位置，也可以是generatorPos/detectorPos）
     * @param sy           查看站的Y坐标
     * @param sz           查看站的Z坐标
     * @param stationTypeId 查看站的类型 ID（可以为空/ GENERIC：不筛选类型，纯距离）
     */
    public static List<SyncOrdersPayload.OrderEntry> getOrdersForStation(int sx, int sy, int sz, String stationTypeId) {
        List<SyncOrdersPayload.OrderEntry> all = ORDERS; // CopyOnWriteArrayList 引用一次即可（迭代安全）
        if (all.isEmpty()) return java.util.List.of();
        final String expectType = stationTypeId;
        final boolean hasType = expectType != null && !expectType.isEmpty()
                && !com.hzldm.createcargodispatch.cargo.StationType.GENERIC.getId().equals(expectType);
        java.util.ArrayList<SyncOrdersPayload.OrderEntry> out = new java.util.ArrayList<>(Math.min(16, all.size()));
        for (SyncOrdersPayload.OrderEntry e : all) {
            // 精确相等（站本体==站本体 或 detectorPos==detectorPos）优先短路
            if (e.startX() == sx && e.startY() == sy && e.startZ() == sz) {
                // 类型匹配才允许放行（hasType=true 时校验；空/泛型默认通过）
                if (!hasType) { out.add(e); continue; }
                String oType = e.sourceStationType();
                if (oType != null && (oType.equals(expectType)
                        || oType.equals(com.hzldm.createcargodispatch.cargo.StationType.GENERIC.getId()))) {
                    out.add(e);
                }
                continue;
            }
            // 类型校验：指定（非泛型）类型时：订单 sourceStationType == 指定类型 OR 订单是泛型 GENERIC
            if (hasType) {
                String oType = e.sourceStationType();
                if (oType == null || oType.isEmpty()) continue;
                if (!oType.equals(expectType) && !oType.equals(com.hzldm.createcargodispatch.cargo.StationType.GENERIC.getId())) {
                    continue;
                }
            }
            // 同站立方距离判定：XZ≤20 / Y≤8（完全匹配 searchNearbyDetectorForOrderPos 搜索阈值）
            int dx = Math.abs(e.startX() - sx);
            int dy = Math.abs(e.startY() - sy);
            int dz = Math.abs(e.startZ() - sz);
            if (dx <= 20 && dy <= 8 && dz <= 20) {
                out.add(e);
            }
        }
        return out;
    }

    /** 便捷重载：按 BlockPos + 类型 ID 过滤（货运站现场 GUI 推荐调用：带类型精确过滤，避免混进同位置其他类型旧缓存） */
    public static List<SyncOrdersPayload.OrderEntry> getOrdersForStation(net.minecraft.core.BlockPos pos, String stationTypeId) {
        if (pos == null) return java.util.List.of();
        return getOrdersForStation(pos.getX(), pos.getY(), pos.getZ(), stationTypeId);
    }

    /** 老签名重载：用于背包远程查看页 —— 类型/坐标从 viewerStationPos / viewerStationType 全局变量取（不与现场 GUI 共享变量） */
    public static List<SyncOrdersPayload.OrderEntry> getOrdersForStation(int sx, int sy, int sz) {
        return getOrdersForStation(sx, sy, sz, viewerStationType);
    }

    /** 老签名便捷重载：仅按 BlockPos 过滤（背包远程查看页场景，类型用 viewerStationType） */
    public static List<SyncOrdersPayload.OrderEntry> getOrdersForStation(net.minecraft.core.BlockPos pos) {
        if (pos == null) return java.util.List.of();
        return getOrdersForStation(pos.getX(), pos.getY(), pos.getZ(), viewerStationType);
    }

    /** 更新玩家活跃订单缓存 */
    public static void updateActiveOrders(List<SyncActiveOrdersPayload.ActiveOrderEntry> orders) {
        ACTIVE_ORDERS.clear();
        ACTIVE_ORDERS.addAll(orders);
    }

    /** 获取玩家活跃订单列表 */
    public static List<SyncActiveOrdersPayload.ActiveOrderEntry> getActiveOrders() {
        return List.copyOf(ACTIVE_ORDERS);
    }

    /** 更新已连接站点缓存 */
    public static void updateLinkages(List<SyncLinkagesPayload.LinkageEntry> linkages) {
        LOGGER.info("[CargoDispatch-Client] 更新已连接站点缓存：{} 个站点", linkages.size());
        for (SyncLinkagesPayload.LinkageEntry e : linkages) {
            LOGGER.info("[CargoDispatch-Client]   - ({},{},{}) {}",
                    e.sourceX(), e.sourceY(), e.sourceZ(), e.sourceType());
        }
        LINKAGES.clear();
        LINKAGES.addAll(linkages);
    }

    /** 获取已连接站点列表 */
    public static List<SyncLinkagesPayload.LinkageEntry> getLinkages() {
        return List.copyOf(LINKAGES);
    }

    /** 玩家是否已在指定货运站建立过联络线（订单页空引导提示用） */
    public static boolean hasLinkageAt(BlockPos pos) {
        return hasLinkageAt(pos, null);
    }

    /**
     * 是否已连接该站结构：精确位置匹配 OR（类型一致 + 同结构半径 XZ≤48/Y≤16）。
     * 原理：连接列表存的是检测器坐标（优先），GUI 查询传的是站本体坐标，必须带距离兜底，
     *       否则「已连接但该站暂无订单」会被误判成「尚未建立联络线」。
     */
    public static boolean hasLinkageAt(BlockPos pos, String stationTypeId) {
        if (pos == null || LINKAGES.isEmpty()) return false;
        for (SyncLinkagesPayload.LinkageEntry e : LINKAGES) {
            if (e.sourceX() == pos.getX() && e.sourceY() == pos.getY() && e.sourceZ() == pos.getZ()) {
                return true;
            }
            if (stationTypeId == null || !stationTypeId.equals(e.sourceType())) continue;
            int dx = Math.abs(e.sourceX() - pos.getX());
            int dy = Math.abs(e.sourceY() - pos.getY());
            int dz = Math.abs(e.sourceZ() - pos.getZ());
            if (dx <= 48 && dz <= 48 && dy <= 16) return true;
        }
        return false;
    }

    /** 玩家公司是否有任意已连接站点 */
    public static boolean hasAnyLinkage() {
        return !LINKAGES.isEmpty();
    }

    /**
     * 客户端添加 Xaero 路径点
     * 原理：
     *  - 切换到客户端主线程执行（Xaero API 必须在主线程调用）
     *  - Xaero 未安装时静默跳过
     *  - 跨维度路径点：Xaero 自动处理维度切换
     *  - 添加成功后将路径点坐标+维度+名称存入 TRACKED_WAYPOINTS，
     *    供 ClientTickWaypointWatcher 做"到达后自动删除"的距离检查
     */
    public static void addWaypoint(int x, int y, int z, String dimension, String name, String initials) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            final ResourceLocation dimId = ResourceLocation.parse(dimension);
            mc.execute(() -> {
                BlockPos pos = new BlockPos(x, y, z);
                Object wp = XaeroWaypointService.addWaypoint(pos, name, initials);
                if (wp != null) {
                    // 添加成功：存入跟踪表，用于到达后自动删除
                    TRACKED_WAYPOINTS.put(name, new TrackedWaypoint(pos, dimId));
                    LOGGER.debug("[CargoDispatch] 跟踪路径点: name={}, pos={}, dim={}", name, pos, dimId);
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("create_cargo_dispatch.cargo.xaero.added"),
                            false);
                }
                // 失败（未安装小地图等）保持静默，不弹聊天/动作栏提示
            });
        }
    }

    /**
     * 客户端删除 Xaero 路径点
     * 原理：
     *  - 订单完成后服务端通知客户端删除对应路径点
     *  - 玩家到达路径点附近时 ClientTickWaypointWatcher 也会调用此处
     *  - 通过名称匹配删除（添加时使用起点类型 + "订单起点 #" + orderId 命名）
     *  - 删除成功后从 TRACKED_WAYPOINTS 移除，防止重复处理
     *  - Xaero 未安装时静默跳过
     */
    public static void removeWaypoint(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            mc.execute(() -> {
                boolean success = XaeroWaypointService.removeWaypoint(name);
                if (success) {
                    // 删除成功：从跟踪表移除
                    TRACKED_WAYPOINTS.remove(name);
                    LOGGER.debug("[CargoDispatch] 停止跟踪路径点并删除成功: name={}", name);
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("create_cargo_dispatch.cargo.xaero.removed"),
                            false);
                } else {
                    // Xaero 中找不到（可能用户手动删了），也从跟踪表移除以避免后续重复尝试
                    Object removed = TRACKED_WAYPOINTS.remove(name);
                    if (removed != null) {
                        LOGGER.debug("[CargoDispatch] Xaero 中找不到路径点但已从跟踪表移除: name={}", name);
                    }
                }
            });
        }
    }
}
