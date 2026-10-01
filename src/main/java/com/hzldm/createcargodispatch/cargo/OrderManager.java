package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 订单管理器（公共门面 / Facade）。
 *
 * <p>本类只保留对外公共 API 并把请求委托给各司其职的包私有组件，外部调用方零感知：</p>
 * <ul>
 *   <li>{@link OrderState}     —— 订单池/计数器/FIFO/调度时间（唯一状态真源）+ 原子读写；</li>
 *   <li>{@link RewardCalculator}—— 奖励计算与随机订单构造（纯函数）；</li>
 *   <li>{@link OrderFactory}   —— 刷新、按需生成、尺寸重算；</li>
 *   <li>{@link OrderJanitor}   —— 死站/超时/按站与全量清理；</li>
 *   <li>{@link OrderLifecycle} —— 接单/放弃/完成/取消/销毁状态机 + 公司归属/在途订单处理；</li>
 *   <li>{@link OrderNotifier}  —— 音效/聊天通知 + 批量合并；</li>
 *   <li>{@link OrderBroadcaster}—— 订单列表数据包同步；</li>
 *   <li>{@link OrderQueries}   —— 只读查询、同站判定、可见性、站点存在性。</li>
 * </ul>
 *
 * <h3>P2-1 幂等结算</h3>
 * 收货完成走「原子认领（{@link #claimForCompletion}）→ 发奖 → {@link #finishClaimedOrder}」，
 * 并发的第二次认领返回 null，杜绝双检测器/检测器+手动提交的重复奖励。
 */
public final class OrderManager {

    /** PENDING 池硬上限（透传 OrderState） */
    public static final int MAX_PENDING_ORDERS = OrderState.MAX_PENDING_ORDERS;
    /** ACCEPTED 池硬上限 */
    public static final int MAX_ACCEPTED_ORDERS = OrderState.MAX_ACCEPTED_ORDERS;

    private OrderManager() {}

    // ==================== 初始化 / 存档恢复 / 时间 ====================

    public static void seed(ServerLevel level) { OrderState.seed(level); }

    public static void setLastRefreshTime(long time) { OrderState.setLastRefreshTime(time); }
    public static void setNextRefreshTicks(long ticks) { OrderState.setNextRefreshTicks(ticks); }

    public static void restorePendingOrder(OrderData order) { OrderState.restorePendingOrder(order); }
    public static void restoreAcceptedOrder(OrderData order) { OrderState.restoreAcceptedOrder(order); }

    // ==================== PENDING 变更通知入口 ====================

    public static void notifyPendingChanged(@Nullable ServerLevel level) { OrderState.afterPendingChanged(level); }

    // ==================== 手工（调试）订单 ====================

    public static boolean registerManualAcceptedOrder(OrderData order, ServerLevel level) {
        return OrderLifecycle.registerManual(order, level);
    }

    public static OrderData deleteManualOrder(String orderId, @Nullable ServerLevel level) {
        return OrderLifecycle.deleteManual(orderId, level);
    }

    // ==================== 订单生成 / 刷新 ====================

    public static void tryRefresh(ServerLevel level) { OrderFactory.tryRefresh(level); }

    public static int forceRefresh(ServerLevel level) { return OrderFactory.forceRefresh(level); }

    public static int ensureOrders(ServerLevel level, UUID playerId, StationType sourceType) {
        return OrderFactory.ensureOrders(level, playerId, sourceType);
    }

    @Nullable
    public static OrderData generateOrderForStation(ServerLevel level, UUID playerId,
                                                     BlockPos sourcePos, StationType sourceType) {
        return OrderFactory.generateOrderForStation(level, playerId, sourcePos, sourceType);
    }

    public static OrderData generateRandomOrder(BlockPos sourcePos, net.minecraft.resources.ResourceLocation sourceDim,
                                                 StationType sourceType, StationType targetType,
                                                 BlockPos targetPos, net.minecraft.resources.ResourceLocation targetDim,
                                                 long gameTime, CargoDimensions dims) {
        return RewardCalculator.randomOrder(sourcePos, sourceDim, sourceType, targetType,
                targetPos, targetDim, gameTime, dims);
    }

    public static int refreshPendingDimensions(ServerLevel level, BlockPos stationPos,
                                               StationType stationType, CargoDimensions newDims) {
        return OrderFactory.refreshPendingDimensions(level, stationPos, stationType, newDims);
    }

    // ==================== 状态机：接单 / 放弃 / 完成 / 取消 / 销毁 ====================

    public static boolean acceptOrder(String orderId, UUID player) {
        return OrderLifecycle.acceptOrder(orderId, player);
    }

    public static OrderData abandonOrder(String orderId) { return OrderLifecycle.abandonOrder(orderId, null); }

    public static OrderData abandonOrder(String orderId, @Nullable ServerLevel level) {
        return OrderLifecycle.abandonOrder(orderId, level);
    }

    public static void completeOrder(String orderId) { OrderLifecycle.completeOrder(orderId, null); }

    public static void completeOrder(String orderId, @Nullable ServerLevel level) {
        OrderLifecycle.completeOrder(orderId, level);
    }

    /** P2-1：原子认领完成结算权 */
    @Nullable
    public static OrderData claimForCompletion(String orderId) {
        return OrderLifecycle.claimForCompletion(orderId);
    }

    /** P2-1：已认领订单完成落盘 */
    public static void finishClaimedOrder(OrderData order, @Nullable ServerLevel level) {
        OrderLifecycle.finishClaimedOrder(order, level);
    }

    public static void cancelOrder(String orderId) { OrderLifecycle.cancelOrder(orderId, null); }

    public static void cancelOrder(String orderId, @Nullable ServerLevel level) {
        OrderLifecycle.cancelOrder(orderId, level);
    }

    public static void cancelOrder(String orderId, Component reason, ServerLevel level) {
        OrderLifecycle.cancelOrder(orderId, reason, level);
    }

    public static OrderData cancelOrderForReturn(String orderId, @Nullable ServerLevel level) {
        return OrderLifecycle.cancelOrderForReturn(orderId, level);
    }

    public static OrderData destroyOrder(String orderId) { return OrderLifecycle.destroyOrder(orderId); }

    @Nullable
    public static OrderData dropAcceptedOrderForDeadStation(String orderId) {
        return OrderLifecycle.dropAcceptedForDeadStation(orderId);
    }

    public static boolean removePendingOrderByIdIfExists(String orderId) {
        return OrderLifecycle.removePendingByIdIfExists(orderId);
    }

    // ==================== 公司归属 / 成员 / 解散 ====================

    public static int convertPersonalOrdersToCompany(ServerLevel level, UUID playerId, UUID companyId) {
        return OrderLifecycle.convertPersonalOrdersToCompany(level, playerId, companyId);
    }

    public static int releasePlayerCompanyOrders(ServerLevel level, UUID playerId, UUID companyId) {
        return OrderLifecycle.releasePlayerCompanyOrders(level, playerId, companyId);
    }

    public static int deleteCompanyOrders(ServerLevel level, UUID companyId) {
        return OrderLifecycle.deleteCompanyOrders(level, companyId);
    }

    // ==================== 清理：站点 / 全量 ====================

    public static void cancelOrdersReferencingStation(ServerLevel level, BlockPos stationPos) {
        OrderJanitor.cancelOrdersReferencingStation(level, stationPos);
    }

    public static void cancelAcceptedOrdersForCompanyAndStation(UUID companyId, BlockPos stationPos,
                                                                StationType stationType, ServerLevel level) {
        OrderJanitor.cancelAcceptedForCompanyAndStation(companyId, stationPos, stationType, level);
    }

    public static int clearPendingOrdersForCompanyDisconnectedStation(UUID companyId, ServerLevel level,
                                                                      BlockPos stationPos, StationType stationType) {
        return OrderJanitor.clearPendingForCompanyDisconnectedStation(companyId, level, stationPos, stationType);
    }

    public static int[] clearOrdersAtStationBlock(ServerLevel level, BlockPos stationPos, StationType stationType) {
        return OrderJanitor.clearOrdersAtStationBlock(level, stationPos, stationType);
    }

    public static int[] clearAllOrders(ServerLevel level) { return OrderJanitor.clearAllOrders(level); }

    public static int pruneExpiredPendingOrders(ServerLevel level) {
        return OrderJanitor.pruneExpiredPendingOrders(level);
    }

    // ==================== 批量通知 ====================

    public static void beginBatchNotification() { OrderNotifier.beginBatch(); }

    public static void endBatchNotification(ServerLevel level) { OrderNotifier.endBatch(level); }

    // ==================== 查询 ====================

    public static OrderData getOrder(String orderId) { return OrderQueries.getOrder(orderId); }

    public static List<OrderData> getPendingOrders() { return OrderQueries.getPendingOrders(); }

    public static List<OrderData> getPendingOrders(StationType stationType) {
        return OrderQueries.getPendingOrders(stationType);
    }

    public static List<OrderData> getPendingOrdersForStation(@Nullable StationType stationType, BlockPos stationPos) {
        return OrderQueries.getPendingOrdersForStation(stationType, stationPos);
    }

    public static List<OrderData> getPendingOrdersForStation(ServerLevel level, UUID viewerId,
                                                             @Nullable StationType stationType, BlockPos stationPos) {
        return OrderQueries.getPendingOrdersForStation(level, viewerId, stationType, stationPos);
    }

    public static List<OrderData> getAllAcceptedOrders() { return OrderQueries.getAllAcceptedOrders(); }

    public static List<OrderData> getAcceptedOrdersByPlayer(UUID playerId) {
        return OrderQueries.getAcceptedOrdersByPlayer(playerId);
    }

    public static List<OrderData> getAcceptedOrdersByPlayer(ServerLevel level, UUID playerId) {
        return OrderQueries.getAcceptedOrdersByPlayer(level, playerId);
    }

    public static boolean isOrderAccessibleAtStation(ServerLevel level, UUID viewerId, OrderData order) {
        return OrderQueries.isOrderAccessibleAtStation(level, viewerId, order);
    }

    public static boolean isOrderStartBelongsToStation(@Nullable OrderData order,
                                                       @Nullable StationType stationType,
                                                       @Nullable BlockPos stationPos) {
        return OrderQueries.isOrderStartBelongsToStation(order, stationType, stationPos);
    }

    public static boolean isOrderVisibleToPlayer(ServerLevel level, UUID playerId, OrderData order) {
        return OrderQueries.isOrderVisibleToPlayer(level, playerId, order);
    }

    public static boolean isSameStationCompat(BlockPos aPos, BlockPos bPos) {
        return OrderQueries.isSameStationCompat(aPos, bPos);
    }

    public static boolean isSameStationTyped(BlockPos aPos, StationType aType, BlockPos bPos, StationType bType) {
        return OrderQueries.isSameStationTyped(aPos, aType, bPos, bType);
    }

    public static boolean sourceAndTargetStationsExistIfLoaded(ServerLevel level, OrderData order) {
        return OrderQueries.sourceAndTargetStationsExistIfLoaded(level, order);
    }

    public static boolean targetStationExistsIfLoaded(ServerLevel level, OrderData order) {
        return OrderQueries.targetStationExistsIfLoaded(level, order);
    }

    public static String findOrderIdByCargoPos(BlockPos cargoPos) {
        return OrderQueries.findOrderIdByCargoPos(cargoPos);
    }

    public static long getRemainingTicks(long currentGameTime) {
        return OrderQueries.getRemainingTicks(currentGameTime);
    }

    public static long getNextRefreshInterval() { return OrderQueries.getNextRefreshInterval(); }

    public static long getLastRefreshTime() { return OrderQueries.getLastRefreshTime(); }

    // ==================== 订单列表数据包 ====================

    public static List<SyncOrdersPayload.OrderEntry> buildFilteredPendingOrderEntriesForPlayer(
            ServerLevel level, UUID playerId) {
        return OrderBroadcaster.filteredForPlayer(level, playerId);
    }

    public static void pushFilteredPendingOrdersToPlayer(ServerLevel level, UUID playerId) {
        OrderBroadcaster.pushToPlayer(level, playerId);
    }

    public static void broadcastPendingOrdersToAllPlayers(ServerLevel level) {
        OrderBroadcaster.broadcast(level);
    }

    /** 接单后标记持久化 */
    public static void markChanged(ServerLevel level) { OrderState.markDirty(level); }

    // ==================== 停服清理 ====================

    public static void clear() { OrderState.clear(); }

    // ========================================================================
    // 服务器 Tick 编排（纯委托：刷新 → 过期扫描 → 合并/兜底广播）
    // ========================================================================

    public static void onServerLevelTick(ServerLevel level) {
        if (level == null || level.dimension() != Level.OVERWORLD) return;
        long now = level.getGameTime();

        // 1) 订单池刷新
        try {
            OrderFactory.tryRefresh(level);
        } catch (Throwable t) {
            OrderState.LOGGER.error("[CargoDispatch] tick：tryRefresh 异常", t);
        }

        // 2) 过期 PENDING 扫描（按配置降频）
        int scanInterval = ModConfig.getOrderExpireScanIntervalTicks();
        if (scanInterval <= 0) scanInterval = 100;
        if (now - OrderState.lastExpireScanTick >= scanInterval) {
            OrderState.lastExpireScanTick = now;
            try {
                OrderJanitor.pruneExpiredPendingOrders(level);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] tick：过期扫描异常", t);
            }
        }

        if (level.getServer() == null || level.getServer().getPlayerCount() <= 0) return;

        // 3) 合并限频广播
        long sched = OrderState.pendingBroadcastScheduledTick.get();
        if (sched != Long.MAX_VALUE && now >= sched) {
            if (OrderState.pendingBroadcastScheduledTick.compareAndSet(sched, Long.MAX_VALUE)) {
                try {
                    OrderBroadcaster.broadcast(level);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] tick：合并广播失败", t);
                }
            }
        }

        // 4) 兜底强制广播（5 秒一次，保证无变化时新玩家也能冷启动同步）
        if (now - OrderState.lastForceBroadcastTick >= OrderState.FORCE_BROADCAST_INTERVAL_TICKS) {
            OrderState.lastForceBroadcastTick = now;
            try {
                OrderBroadcaster.broadcast(level);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] tick：兜底广播失败", t);
            }
        }
    }
}
