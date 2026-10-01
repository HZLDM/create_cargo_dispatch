package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.company.CompanyStore;
import com.hzldm.createcargodispatch.network.ModPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 订单清理者（包私有，SRP：负责死站订单、超时未接订单、按站/全量订单的删除与取消）。
 *
 * <p>本类只做「移除/取消 + 销毁货箱」；取消的订单状态机走 {@link OrderLifecycle}，
 * 超时玩家通知走 {@link OrderNotifier}，状态读写走 {@link OrderState}，
 * 列表数据包最终同步走 {@link OrderBroadcaster}。</p>
 *
 * <h3>同结构匹配</h3>
 * 订单位置与被清站位置：精确相等，或类型一致 + XZ≤48、Y≤16（兼容站本体/检测器口径不一致）。
 */
final class OrderJanitor {

    private OrderJanitor() {}

    /** 订单端是否命中被清站（精确相等或同结构半径） */
    private static boolean stationMatchesOrderSide(StationType expectedType, long expectedPosPacked, BlockPos expectedPos,
                                                   StationType orderSideType, BlockPos orderSidePos) {
        if (orderSidePos == null || expectedPos == null) return false;
        if (orderSidePos.asLong() == expectedPosPacked) return true;
        if (expectedType == null || orderSideType == null || expectedType != orderSideType) return false;
        int dx = Math.abs(orderSidePos.getX() - expectedPos.getX());
        int dz = Math.abs(orderSidePos.getZ() - expectedPos.getZ());
        int dy = Math.abs(orderSidePos.getY() - expectedPos.getY());
        return dx <= 48 && dz <= 48 && dy <= 16;
    }

    // ==================== 死站订单惰性清理 ====================

    static void pruneDeadStationOrders(ServerLevel level) {
        if (level == null || OrderState.PENDING_ORDERS.isEmpty()) return;
        List<String> toRemove = null;
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            boolean dead = false;
            BlockPos sp = order.getStartPos();
            if (sp == null) dead = true;
            else if (!OrderQueries.stationBlockExistsWithFallback(level, order.getStationType(), sp)) dead = true;

            if (!dead) {
                BlockPos tp = order.getTargetPos();
                if (tp == null) dead = true;
                else if (!OrderQueries.stationBlockExistsWithFallback(level, order.getTargetStationType(), tp)) dead = true;
            }
            if (dead) {
                if (toRemove == null) toRemove = new ArrayList<>(4);
                toRemove.add(order.getOrderId());
            }
        }
        if (toRemove != null && !toRemove.isEmpty()) {
            for (String id : toRemove) OrderState.removePendingOrder(id);
            OrderState.markDirty(level);
            OrderState.LOGGER.info("[CargoDispatch] pruneDeadStationOrders：清理 {} 个引用已拆站的 PENDING", toRemove.size());
        }
    }

    // ==================== 站点被破坏：取消引用它的订单 ====================

    static void cancelOrdersReferencingStation(ServerLevel level, BlockPos stationPos) {
        if (level == null || stationPos == null) return;
        final long packedPos = stationPos.asLong();

        List<String> pendingToRemove = new ArrayList<>(4);
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            if (referencesStation(order, packedPos)) pendingToRemove.add(order.getOrderId());
        }
        int removedPending = 0;
        for (String id : pendingToRemove) {
            if (OrderState.removePendingOrder(id) != null) removedPending++;
        }

        List<String> acceptedToCancel = new ArrayList<>(4);
        for (OrderData order : OrderState.ACCEPTED_ORDERS.values()) {
            if (referencesStation(order, packedPos)) acceptedToCancel.add(order.getOrderId());
        }
        int cancelledAccepted = 0;
        for (String id : acceptedToCancel) {
            Component reason = Component.translatable("create_cargo_dispatch.order.cancel_reason_station_removed")
                    .append(String.format(" (%d,%d,%d)", stationPos.getX(), stationPos.getY(), stationPos.getZ()));
            OrderLifecycle.cancelOrder(id, reason, level);
            cancelledAccepted++;
        }

        if (removedPending > 0 || cancelledAccepted > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcast(level.getGameTime() + OrderState.BROADCAST_THROTTLE_TICKS);
            OrderState.LOGGER.info("[CargoDispatch] cancelOrdersReferencingStation(station={})：移除 {} PENDING，取消 {} ACCEPTED",
                    stationPos, removedPending, cancelledAccepted);
        }
    }

    private static boolean referencesStation(OrderData order, long packedPos) {
        if (order.getStartPos() != null && order.getStartPos().asLong() == packedPos) return true;
        return order.getTargetPos() != null && order.getTargetPos().asLong() == packedPos;
    }

    // ==================== 超时未接订单扫描 ====================

    static int pruneExpiredPendingOrders(ServerLevel level) {
        if (level == null || OrderState.PENDING_ORDERS.isEmpty()) return 0;
        final long now = level.getGameTime();
        List<String> toRemoveIds = null;
        List<OrderData> expiredSnapshots = null;
        for (OrderData order : new ArrayList<>(OrderState.PENDING_ORDERS.values())) {
            if (order == null || order.getStatus() != OrderData.Status.PENDING) continue;
            long expireAt = order.getExpireAtGameTime();
            if (expireAt <= 0L || expireAt > now) continue;
            if (toRemoveIds == null) {
                toRemoveIds = new ArrayList<>(8);
                expiredSnapshots = new ArrayList<>(8);
            }
            toRemoveIds.add(order.getOrderId());
            expiredSnapshots.add(order);
        }
        int removed = 0;
        if (toRemoveIds != null) {
            for (String id : toRemoveIds) {
                if (OrderState.removePendingOrder(id) != null) removed++;
            }
        }
        if (removed > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcast(level.getGameTime() + OrderState.BROADCAST_THROTTLE_TICKS);
            OrderState.LOGGER.info("[CargoDispatch] pruneExpiredPendingOrders：超时移除 {} 个 PENDING（now={}）", removed, now);
            if (expiredSnapshots != null && !expiredSnapshots.isEmpty()) {
                try {
                    OrderNotifier.expired(level, expiredSnapshots);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] 发送超时通知失败", t);
                }
            }
        }
        return removed;
    }

    // ==================== 公司断开站点 ====================

    /**
     * 公司断开/站点被拆：取消该公司成员配送中的相关 ACCEPTED 订单（销毁货箱 + 通知）。
     *
     * <p>成员判定以 {@link CompanyStore} 持久化归属为权威（<b>含离线成员</b>）；
     * 不再使用 LinkageManager 仅含在线玩家的内存索引——否则未登录成员的在途订单会漏处理，
     * 留下无主的活跃订单与货箱。</p>
     */
    static void cancelAcceptedForCompanyAndStation(UUID companyId,
                                                   BlockPos stationPos, StationType stationType, ServerLevel level) {
        if (companyId == null || stationPos == null || level == null) return;
        final long packedStation = stationPos.asLong();
        final CompanyStore companyStore = CompanyStore.get(level);
        List<String> toCancel = null;
        for (OrderData order : new ArrayList<>(OrderState.ACCEPTED_ORDERS.values())) {
            if (order == null || !companyId.equals(order.getOwnerCompany())) continue;
            UUID hauler = order.getAcceptedPlayer();
            // 权威成员判定：在线/离线均可命中
            if (hauler == null || !companyStore.isMember(companyId, hauler)) continue;
            boolean matchStart = stationMatchesOrderSide(stationType, packedStation, stationPos,
                    order.getStationType(), order.getStartPos());
            boolean matchTarget = stationMatchesOrderSide(stationType, packedStation, stationPos,
                    order.getTargetStationType(), order.getTargetPos());
            if (!matchStart && !matchTarget) continue;
            if (toCancel == null) toCancel = new ArrayList<>(4);
            toCancel.add(order.getOrderId());
        }
        if (toCancel == null || toCancel.isEmpty()) return;

        int cancelledCount = 0, removedCargoCount = 0;
        for (String orderId : toCancel) {
            OrderData order = OrderState.ACCEPTED_ORDERS.get(orderId);
            if (order == null) continue;
            try {
                ModPayloads.removeCargoBlocks(level, order);
                removedCargoCount++;
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] 公司断站：订单 {} 删货箱失败", orderId, t);
            }
            boolean startIsStation = stationMatchesOrderSide(stationType, packedStation, stationPos,
                    order.getStationType(), order.getStartPos());
            String whichSide = startIsStation ? "起始站" : "目标站";
            Component reason = Component.translatable("create_cargo_dispatch.order.cancel_reason_player_disconnected",
                    whichSide, orderId, stationPos.getX(), stationPos.getY(), stationPos.getZ());
            OrderLifecycle.cancelOrder(orderId, reason, level);
            cancelledCount++;
        }
        if (cancelledCount > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcast(level.getGameTime() + OrderState.BROADCAST_THROTTLE_TICKS);
            OrderState.LOGGER.info("[CargoDispatch] cancelAcceptedForCompanyAndStation(company={},station={},type={})：取消 {} 单，销毁 {} 货箱",
                    companyId, stationPos, stationType != null ? stationType.getId() : "?", cancelledCount, removedCargoCount);
        }
    }

    static int clearPendingForCompanyDisconnectedStation(UUID companyId, ServerLevel level,
                                                          BlockPos stationPos, StationType stationType) {
        if (companyId == null || stationPos == null) return 0;
        final long packed = stationPos.asLong();
        List<String> toRemove = null;
        for (OrderData order : new ArrayList<>(OrderState.PENDING_ORDERS.values())) {
            if (order == null || !companyId.equals(order.getOwnerCompany())) continue;
            if (!stationMatchesOrderSide(stationType, packed, stationPos, order.getStationType(), order.getStartPos())) continue;
            if (toRemove == null) toRemove = new ArrayList<>(8);
            toRemove.add(order.getOrderId());
        }
        int removed = 0;
        if (toRemove != null) {
            for (String id : toRemove) if (OrderState.removePendingOrder(id) != null) removed++;
        }
        if (removed > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcast(level.getGameTime() + OrderState.BROADCAST_THROTTLE_TICKS);
            OrderState.LOGGER.info("[CargoDispatch] clearPendingForCompanyDisconnectedStation(company={},pos={},type={})：移除 {} 单",
                    companyId, stationPos, stationType != null ? stationType.getId() : "?", removed);
        }
        return removed;
    }

    // ==================== 管理指令：按站 / 全清 ====================

    /** @return [pendingRemoved, acceptedCancelled] */
    static int[] clearOrdersAtStationBlock(ServerLevel level, BlockPos stationPos, StationType stationType) {
        if (level == null || stationPos == null) return new int[]{0, 0};
        final long packed = stationPos.asLong();

        List<String> pendingIds = null;
        for (OrderData order : new ArrayList<>(OrderState.PENDING_ORDERS.values())) {
            boolean s = stationMatchesOrderSide(stationType, packed, stationPos, order.getStationType(), order.getStartPos());
            boolean t = stationMatchesOrderSide(stationType, packed, stationPos, order.getTargetStationType(), order.getTargetPos());
            if (!s && !t) continue;
            if (pendingIds == null) pendingIds = new ArrayList<>(8);
            pendingIds.add(order.getOrderId());
        }
        int removedPending = 0;
        if (pendingIds != null) for (String id : pendingIds) if (OrderState.removePendingOrder(id) != null) removedPending++;

        int cancelledAccepted = 0, removedCargos = 0;
        List<String> acceptIds = null;
        for (OrderData order : new ArrayList<>(OrderState.ACCEPTED_ORDERS.values())) {
            boolean s = stationMatchesOrderSide(stationType, packed, stationPos, order.getStationType(), order.getStartPos());
            boolean t = stationMatchesOrderSide(stationType, packed, stationPos, order.getTargetStationType(), order.getTargetPos());
            if (!s && !t) continue;
            if (acceptIds == null) acceptIds = new ArrayList<>(8);
            acceptIds.add(order.getOrderId());
        }
        if (acceptIds != null) {
            for (String orderId : acceptIds) {
                OrderData order = OrderState.ACCEPTED_ORDERS.get(orderId);
                if (order == null) continue;
                try {
                    ModPayloads.removeCargoBlocks(level, order);
                    removedCargos++;
                } catch (Throwable tt) {
                    OrderState.LOGGER.error("[CargoDispatch] clearOrdersAtStationBlock 订单 {} 删货箱失败", orderId, tt);
                }
                Component reason = Component.translatable("create_cargo_dispatch.order.cancel_reason_station_cleared",
                        order.getStationType().getId(), order.getTargetStationType().getId(),
                        stationPos.getX(), stationPos.getY(), stationPos.getZ());
                OrderLifecycle.cancelOrder(orderId, reason, level);
                cancelledAccepted++;
            }
        }
        if (removedPending > 0 || cancelledAccepted > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcast(level.getGameTime() + OrderState.BROADCAST_THROTTLE_TICKS);
            OrderState.LOGGER.info("[CargoDispatch] clearOrdersAtStationBlock(pos={},type={})：PENDING-{} ACCEPTED-{} 货箱-{}",
                    stationPos, stationType != null ? stationType.getId() : "?", removedPending, cancelledAccepted, removedCargos);
        }
        return new int[]{removedPending, cancelledAccepted};
    }

    /** @return [pendingCleared, acceptedCancelled] */
    static int[] clearAllOrders(ServerLevel level) {
        if (level == null) return new int[]{0, 0};
        int pendingCleared = OrderState.PENDING_ORDERS.size();
        OrderState.clearPending();

        int acceptedCancelled = 0, removedCargos = 0;
        if (!OrderState.ACCEPTED_ORDERS.isEmpty()) {
            List<String> all = new ArrayList<>(OrderState.ACCEPTED_ORDERS.keySet());
            for (String orderId : all) {
                OrderData order = OrderState.ACCEPTED_ORDERS.get(orderId);
                if (order == null) continue;
                try {
                    ModPayloads.removeCargoBlocks(level, order);
                    removedCargos++;
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] clearAllOrders 订单 {} 删货箱失败", orderId, t);
                }
                Component reason = Component.translatable("create_cargo_dispatch.order.cancel_reason_cleared_all", orderId);
                OrderLifecycle.cancelOrder(orderId, reason, level);
                acceptedCancelled++;
            }
            OrderState.ACCEPTED_ORDER_IDS_FIFO.clear();
        }
        OrderState.markDirty(level);
        OrderBroadcaster.broadcast(level);
        OrderState.LOGGER.info("[CargoDispatch] clearAllOrders：PENDING-{} ACCEPTED-{} 货箱-{}",
                pendingCleared, acceptedCancelled, removedCargos);
        return new int[]{pendingCleared, acceptedCancelled};
    }
}
