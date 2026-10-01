package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单共享状态（包私有，SRP：唯一持有订单池、计数器、FIFO、刷新/调度时间的真源）。
 *
 * <p>各职责类（Factory/Janitor/Lifecycle/Notifier/Broadcaster/Queries）只通过本类
 * 读写状态，禁止各自缓存池引用，保证「单一事实来源」。</p>
 *
 * <h3>内存保护</h3>
 * 池有硬上限 + FIFO 淘汰最老订单；所有 put/remove 必须经本类原子方法，保证 FIFO 与池一致。
 */
final class OrderState {

    static final Logger LOGGER = LoggerFactory.getLogger("CreateCargoDispatch");

    private OrderState() {}

    // ==================== 订单池 ====================
    static final ConcurrentHashMap<String, OrderData> PENDING_ORDERS = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, OrderData> ACCEPTED_ORDERS = new ConcurrentHashMap<>();

    /** 预聚合：按 sourceType 统计 PENDING 数，O(1) */
    static final ConcurrentHashMap<StationType, java.util.concurrent.atomic.AtomicInteger> pendingOrderCountByType =
            new ConcurrentHashMap<>();

    /** 预聚合：按具体起始站位置统计 PENDING 数，O(1)（每站点独立上限） */
    static final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> pendingOrderCountBySourcePos =
            new ConcurrentHashMap<>();

    // ==================== 硬上限 + FIFO ====================
    /** PENDING 池硬上限（外部可见常量，门面透传） */
    public static final int MAX_PENDING_ORDERS = 4096;
    /** ACCEPTED 池硬上限 */
    public static final int MAX_ACCEPTED_ORDERS = 2048;

    static final ConcurrentLinkedDeque<String> PENDING_ORDER_IDS_FIFO = new ConcurrentLinkedDeque<>();
    static final ConcurrentLinkedDeque<String> ACCEPTED_ORDER_IDS_FIFO = new ConcurrentLinkedDeque<>();

    // ==================== 刷新 / 过期 / 广播调度时间 ====================
    /** 距下次刷新剩余 tick */
    static long nextRefreshTicks = 0;
    /** 上次刷新游戏时间 */
    static long lastRefreshTime = 0;
    /** 上次过期扫描执行 tick（降频用） */
    static long lastExpireScanTick = 0L;

    /** 广播最小间隔（默认 2 秒） */
    static final int BROADCAST_THROTTLE_TICKS = 20 * 2;
    /** 兜底强制广播间隔（5 秒） */
    static final int FORCE_BROADCAST_INTERVAL_TICKS = 20 * 5;
    /** 待广播最早允许 tick（MAX_VALUE=无调度；CAS 保证并发安全） */
    static final java.util.concurrent.atomic.AtomicLong pendingBroadcastScheduledTick =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    /** 兜底广播上次执行 tick */
    static long lastForceBroadcastTick = 0L;

    // ==================== 批量通知缓冲状态 ====================
    static final Object BATCH_NOTIFY_LOCK = new Object();
    static boolean inBatchNotification = false;
    /** 玩家 → 本批次累计要通知的订单 */
    static final java.util.Map<java.util.UUID, java.util.List<OrderData>> batchedOrdersByPlayer =
            new ConcurrentHashMap<>();
    static int batchedOrderCount = 0;

    // =========================================================================
    // PENDING 池原子读写
    // =========================================================================

    /** 写入 PENDING（维护 FIFO + 计数 + 超量淘汰最老）；同 id 覆盖不重复入队 */
    static void addPendingOrder(OrderData order) {
        if (order == null || order.getOrderId() == null) return;
        String id = order.getOrderId();
        OrderData prev = PENDING_ORDERS.put(id, order);
        if (prev != null && id.equals(prev.getOrderId())) {
            // 覆盖：不重入 FIFO；但同步新旧类型/位置计数
            StationType prevType = prev.getStationType();
            StationType newType = order.getStationType();
            BlockPos prevPos = prev.getStartPos();
            BlockPos newPos = order.getStartPos();
            if (prevType != newType) {
                if (prevType != null) decCountByType(prevType);
                if (newType != null) incCountByType(newType);
            }
            if ((prevPos == null) != (newPos == null) || (prevPos != null && !prevPos.equals(newPos))) {
                if (prevPos != null) decCountBySourcePos(prevPos);
                if (newPos != null) incCountBySourcePos(newPos);
            }
            return;
        }
        if (order.getStationType() != null) incCountByType(order.getStationType());
        if (order.getStartPos() != null) incCountBySourcePos(order.getStartPos());
        PENDING_ORDER_IDS_FIFO.offerLast(id);
        while (PENDING_ORDERS.size() > MAX_PENDING_ORDERS) {
            String eldestId = PENDING_ORDER_IDS_FIFO.pollFirst();
            if (eldestId == null) break;
            OrderData evicted = PENDING_ORDERS.remove(eldestId);
            if (evicted == null) continue; // FIFO 残留无效 id
            if (evicted.getStationType() != null) decCountByType(evicted.getStationType());
            if (evicted.getStartPos() != null) decCountBySourcePos(evicted.getStartPos());
            LOGGER.warn("[CargoDispatch] PENDING 池达硬上限 {}，淘汰最老未接单 {}（{}→{}）防 OOM",
                    MAX_PENDING_ORDERS, eldestId,
                    evicted.getStationType() != null ? evicted.getStationType().getId() : "?",
                    evicted.getTargetStationType() != null ? evicted.getTargetStationType().getId() : "?");
        }
    }

    /** 从 PENDING 原子移除（清理 FIFO + 计数） */
    static OrderData removePendingOrder(String orderId) {
        if (orderId == null) return null;
        OrderData removed = PENDING_ORDERS.remove(orderId);
        if (removed != null) {
            PENDING_ORDER_IDS_FIFO.removeFirstOccurrence(orderId);
            if (removed.getStationType() != null) decCountByType(removed.getStationType());
            if (removed.getStartPos() != null) decCountBySourcePos(removed.getStartPos());
        }
        return removed;
    }

    // =========================================================================
    // ACCEPTED 池原子读写
    // =========================================================================

    /** 写入 ACCEPTED（维护 FIFO + 超量淘汰最老并解绑） */
    static void addAcceptedOrder(OrderData order) {
        if (order == null || order.getOrderId() == null) return;
        String id = order.getOrderId();
        OrderData prev = ACCEPTED_ORDERS.put(id, order);
        if (prev != null && id.equals(prev.getOrderId())) return;
        ACCEPTED_ORDER_IDS_FIFO.offerLast(id);
        while (ACCEPTED_ORDERS.size() > MAX_ACCEPTED_ORDERS) {
            String eldestId = ACCEPTED_ORDER_IDS_FIFO.pollFirst();
            if (eldestId == null) break;
            OrderData evicted = ACCEPTED_ORDERS.remove(eldestId);
            if (evicted == null) continue;
            LOGGER.warn("[CargoDispatch] ACCEPTED 池达硬上限 {}，淘汰最老已接单 {}（接单人={}）",
                    MAX_ACCEPTED_ORDERS, eldestId, evicted.getAcceptedPlayer());
            OrderPlayerBinding.unbind(eldestId); // 防绑定泄漏
        }
    }

    /** 从 ACCEPTED 原子移除（清理 FIFO）。订单完成/取消/认领用 */
    static OrderData removeAcceptedOrder(String orderId) {
        if (orderId == null) return null;
        OrderData removed = ACCEPTED_ORDERS.remove(orderId);
        if (removed != null) {
            ACCEPTED_ORDER_IDS_FIFO.removeFirstOccurrence(orderId);
        }
        return removed;
    }

    // =========================================================================
    // 预聚合计数器
    // =========================================================================

    static void incCountByType(StationType type) {
        pendingOrderCountByType.computeIfAbsent(type, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }
    static void decCountByType(StationType type) {
        java.util.concurrent.atomic.AtomicInteger cnt = pendingOrderCountByType.get(type);
        if (cnt != null) cnt.decrementAndGet();
    }
    static void incCountBySourcePos(BlockPos pos) {
        if (pos == null) return;
        pendingOrderCountBySourcePos.computeIfAbsent(pos.asLong(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }
    static void decCountBySourcePos(BlockPos pos) {
        if (pos == null) return;
        java.util.concurrent.atomic.AtomicInteger cnt = pendingOrderCountBySourcePos.get(pos.asLong());
        if (cnt != null) cnt.decrementAndGet();
    }

    /** 指定具体站点 PENDING 数（每站上限检查用） */
    static int countOrdersBySourcePos(BlockPos pos) {
        if (pos == null) return 0;
        java.util.concurrent.atomic.AtomicInteger cnt = pendingOrderCountBySourcePos.get(pos.asLong());
        return cnt != null && cnt.get() > 0 ? cnt.get() : 0;
    }

    /** 指定源类型 PENDING 数（并发瞬时负数兜底 0） */
    static int countOrdersBySourceType(StationType sourceType) {
        if (sourceType == null) return 0;
        java.util.concurrent.atomic.AtomicInteger cnt = pendingOrderCountByType.get(sourceType);
        return cnt != null && cnt.get() > 0 ? cnt.get() : 0;
    }

    // =========================================================================
    // 广播调度
    // =========================================================================

    /** 调度一次广播（2 秒内重复自动合并为最早一次） */
    static void schedulePendingBroadcast(long earliestTick) {
        pendingBroadcastScheduledTick.accumulateAndGet(earliestTick, Math::min);
    }

    /** PENDING 变更：按 level 时间调度（throttle 合并）；level==null 用 0 尽快广播 */
    static void afterPendingChanged(ServerLevel level) {
        schedulePendingBroadcast(level != null
                ? level.getGameTime() + BROADCAST_THROTTLE_TICKS : 0L);
    }

    /** 用户明确操作：下一次 tick 立即广播（不合并） */
    static void schedulePendingBroadcastImmediate(ServerLevel level) {
        schedulePendingBroadcast(level != null ? level.getGameTime() : 0L);
    }

    static void resetPendingBroadcastSchedule() {
        pendingBroadcastScheduledTick.set(Long.MAX_VALUE);
    }

    // =========================================================================
    // 刷新间隔 / 初始化 / 存档恢复
    // =========================================================================

    /** 配置 min~max 间随机刷新间隔 */
    static long randomRefreshInterval() {
        long min = ModConfig.getOrderRefreshMinTicks();
        long max = ModConfig.getOrderRefreshMaxTicks();
        if (max <= min) return min;
        return min + ThreadLocalRandom.current().nextLong(max - min + 1);
    }

    /** 世界加载初始化时间基准（OrderStore 已先加载） */
    static void seed(ServerLevel level) {
        if (lastRefreshTime == 0) lastRefreshTime = level.getGameTime();
        if (nextRefreshTicks == 0) nextRefreshTicks = randomRefreshInterval();
        LOGGER.info("[CargoDispatch] 订单系统初始化：刷新基准 {}，下次间隔 {}，PENDING {}，ACCEPTED {}",
                lastRefreshTime, nextRefreshTicks, PENDING_ORDERS.size(), ACCEPTED_ORDERS.size());
    }

    static void setLastRefreshTime(long time) { lastRefreshTime = time; }
    static void setNextRefreshTicks(long ticks) { nextRefreshTicks = ticks; }

    /** 标记订单持久化存储为脏（level 为 null 时忽略，如内存态） */
    static void markDirty(ServerLevel level) {
        if (level != null) OrderStore.markDirty(level);
    }

    /** 存档恢复订单（走统一入口防超量） */
    static void restorePendingOrder(OrderData order) { addPendingOrder(order); }
    static void restoreAcceptedOrder(OrderData order) { addAcceptedOrder(order); }

    /** 只清空 PENDING 相关（保留 ACCEPTED；强制刷新用） */
    static void clearPending() {
        PENDING_ORDERS.clear();
        PENDING_ORDER_IDS_FIFO.clear();
        pendingOrderCountByType.clear();
        pendingOrderCountBySourcePos.clear();
    }

    /** 停服清理全部内存状态 */
    static void clear() {
        PENDING_ORDERS.clear();
        PENDING_ORDER_IDS_FIFO.clear();
        pendingOrderCountByType.clear();
        pendingOrderCountBySourcePos.clear();
        ACCEPTED_ORDERS.clear();
        ACCEPTED_ORDER_IDS_FIFO.clear();
        lastRefreshTime = 0;
        lastExpireScanTick = 0L;
        lastForceBroadcastTick = 0L;
        synchronized (BATCH_NOTIFY_LOCK) {
            inBatchNotification = false;
            batchedOrdersByPlayer.clear();
            batchedOrderCount = 0;
        }
        resetPendingBroadcastSchedule();
    }
}
