package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import com.hzldm.createcargodispatch.network.ModPayloads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单生命周期（包私有，SRP：订单状态机——接单/放弃/完成/取消/销毁，
 * 以及公司创建/加入/成员退出/解散引发的订单归属与在途订单处理）。
 *
 * <p>本类只改变订单状态与归属，不负责「生成什么样的订单」（{@link OrderFactory}）、
 * 不负责音效聊天（{@link OrderNotifier}）、不发列表数据包（{@link OrderBroadcaster}）。</p>
 *
 * <h3>状态机</h3>
 * PENDING ⇄ ACCEPTED → COMPLETED；销毁/死站则直接丢弃。
 */
final class OrderLifecycle {

    private OrderLifecycle() {}

    // ==================== 接单 ====================

    /** 调试货箱放置的手工订单：直接入 ACCEPTED（不经公共 PENDING），重复 id 幂等忽略 */
    static boolean registerManual(OrderData order, @Nullable ServerLevel level) {
        if (order == null || order.getOrderId() == null) return false;
        if (OrderState.ACCEPTED_ORDERS.containsKey(order.getOrderId())
                || OrderState.PENDING_ORDERS.containsKey(order.getOrderId())) {
            return false;
        }
        order.setStatus(OrderData.Status.ACCEPTED);
        order.setManualDebug(true);
        OrderState.addAcceptedOrder(order);
        OrderPlayerBinding.bind(order.getOrderId(), order.getAcceptedPlayer());
        OrderState.markDirty(level);
        return true;
    }

    /** 删除手工订单：仅摘除并解绑，不回公共池 */
    @Nullable
    static OrderData deleteManual(String orderId, @Nullable ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order == null) order = OrderState.removePendingOrder(orderId);
        if (order != null) {
            order.setStatus(OrderData.Status.CANCELLED);
            OrderPlayerBinding.unbind(orderId);
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcastImmediate(level);
        }
        return order;
    }

    static boolean acceptOrder(String orderId, UUID player) {
        OrderData order = OrderState.PENDING_ORDERS.get(orderId);
        if (order == null || !order.isAcceptable()) return false;
        if (OrderState.removePendingOrder(orderId) == null) return false;
        order.setStatus(OrderData.Status.ACCEPTED);
        order.setAcceptedPlayer(player);
        OrderState.addAcceptedOrder(order);
        return true;
    }

    // ==================== 完成 ====================

    static void completeOrder(String orderId, @Nullable ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order != null) {
            order.setStatus(OrderData.Status.COMPLETED);
            OrderState.markDirty(level);
        }
    }

    /**
     * 原子认领完成结算权（P2-1）：从 ACCEPTED 摘除，仅成功认领者可发奖/完成/通知。
     */
    @Nullable
    static OrderData claimForCompletion(String orderId) {
        if (orderId == null) return null;
        return OrderState.removeAcceptedOrder(orderId);
    }

    /** 已认领订单标记完成并落盘 */
    static void finishClaimedOrder(OrderData order, @Nullable ServerLevel level) {
        if (order == null) return;
        order.setStatus(OrderData.Status.COMPLETED);
        OrderState.markDirty(level);
    }

    // ==================== 放弃（回 PENDING） ====================

    static OrderData abandonOrder(String orderId, @Nullable ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order != null) {
            resetForReturnToPending(level, order);
            OrderState.addPendingOrder(order);
            OrderState.schedulePendingBroadcastImmediate(level);
            OrderState.markDirty(level);
            OrderState.LOGGER.info("[CargoDispatch] 订单 {} 被放弃，回 PENDING", orderId);
        }
        return order;
    }

    // ==================== 取消 ====================

    /** 取消（玩家主动/生成器空间不足）：回 PENDING 并尽快广播 */
    static void cancelOrder(String orderId, @Nullable ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order != null) {
            resetForReturnToPending(level, order);
            OrderState.addPendingOrder(order);
            OrderState.schedulePendingBroadcastImmediate(level);
            OrderState.markDirty(level);
        }
    }

    /**
     * 取消（带原因通知，站点被破坏时用）：订单不回 PENDING（站点已拆）。
     */
    static void cancelOrder(String orderId, @Nullable Component reason, ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order == null) {
            // 可能是 PENDING 单
            OrderData pendingRemoved = OrderState.removePendingOrder(orderId);
            if (pendingRemoved != null) {
                OrderState.afterPendingChanged(level);
                OrderState.markDirty(level);
            }
            return;
        }
        UUID playerId = order.getAcceptedPlayer();
        if (playerId != null) {
            OrderPlayerBinding.unbind(orderId);
            if (level != null && level.getServer() != null) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) {
                    try {
                        if (reason != null) player.sendSystemMessage(reason);
                        ModPayloads.notifyRemoveWaypoint(orderId, player);
                    } catch (Throwable t) {
                        OrderState.LOGGER.error("[CargoDispatch] cancelOrder 通知玩家失败 order={}", orderId, t);
                    }
                }
            }
        }
        order.setStatus(OrderData.Status.PENDING);
        order.setAcceptedPlayer(null);
        OrderState.markDirty(level);
        OrderState.LOGGER.info("[CargoDispatch] 订单 {} 取消（站点被破坏），原因：{}",
                orderId, reason != null ? reason.getString() : "无");
    }

    /** 取消并返回订单（创造破坏货箱时需读取接单玩家） */
    @Nullable
    static OrderData cancelOrderForReturn(String orderId, @Nullable ServerLevel level) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order != null) {
            UUID keepPlayer = order.getAcceptedPlayer(); // reset 会清，调用方需要读取
            resetForReturnToPending(level, order);
            order.setAcceptedPlayer(keepPlayer);
            OrderState.addPendingOrder(order);
            OrderState.afterPendingChanged(level);
            OrderState.LOGGER.info("[CargoDispatch] 订单 {} 已取消（货箱被创造破坏），回 PENDING", orderId);
        }
        return order;
    }

    // ==================== 销毁 / 死站丢弃 ====================

    /** 货物损坏：从两池彻底删除（不回 PENDING） */
    static OrderData destroyOrder(String orderId) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order == null) order = OrderState.removePendingOrder(orderId);
        if (order != null) order.setStatus(OrderData.Status.COMPLETED);
        return order;
    }

    /** ACCEPTED 阶段发现站点已拆：摘除并丢弃（不回 PENDING） */
    @Nullable
    static OrderData dropAcceptedForDeadStation(String orderId) {
        OrderData order = OrderState.removeAcceptedOrder(orderId);
        if (order != null) {
            order.setStatus(OrderData.Status.PENDING);
            order.setAcceptedPlayer(null);
            OrderPlayerBinding.unbind(orderId);
            OrderState.LOGGER.info("[CargoDispatch] 订单 {} 在 ACCEPTED 阶段因站点被拆丢弃", orderId);
            return order;
        }
        return null;
    }

    /** 防御性清理：PENDING 中仍存在则移除 */
    static boolean removePendingByIdIfExists(String orderId) {
        if (orderId == null) return false;
        boolean removed = OrderState.removePendingOrder(orderId) != null;
        return removed;
    }

    // ==================== 回 PENDING 字段重置 ====================

    /** 回 PENDING：重置状态/玩家/SubLevel 引用/过期时间/创建时间（不改起终位置） */
    private static void resetForReturnToPending(@Nullable ServerLevel level, OrderData order) {
        if (order == null) return;
        order.setStatus(OrderData.Status.PENDING);
        order.setAcceptedPlayer(null);
        order.setSubLevelUuid(null);
        if (level != null) {
            long now = level.getGameTime();
            long expireMin = ModConfig.getOrderExpireMinTicks();
            long expireMax = ModConfig.getOrderExpireMaxTicks();
            if (expireMin <= 0L && expireMax <= 0L) {
                order.setExpireAtGameTime(0L);
            } else {
                long ttl = expireMax > expireMin
                        ? ThreadLocalRandom.current().nextLong(expireMin, expireMax + 1L) : expireMin;
                order.setExpireAtGameTime(now + ttl);
            }
            order.setCreatedGameTime(now);
        }
    }

    // ==================== 公司创建/加入：个人订单转公司 ====================

    static int convertPersonalOrdersToCompany(ServerLevel level, UUID playerId, UUID companyId) {
        if (playerId == null || companyId == null) return 0;
        int changed = 0;
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            if (playerId.equals(order.getOwnerPlayer())) {
                order.setOwnerPlayer(null);
                order.setOwnerCompany(companyId);
                changed++;
            }
        }
        for (OrderData order : OrderState.ACCEPTED_ORDERS.values()) {
            if (playerId.equals(order.getOwnerPlayer())) {
                order.setOwnerPlayer(null);
                order.setOwnerCompany(companyId);
                changed++;
            }
        }
        if (changed > 0) {
            OrderState.markDirty(level);
            OrderState.LOGGER.info("[CargoDispatch] 玩家 {} 加入公司 {}，{} 个人单转公司单", playerId, companyId, changed);
        }
        return changed;
    }

    // ==================== 成员退出/被踢：在途订单回公司池 ====================

    static int releasePlayerCompanyOrders(ServerLevel level, UUID playerId, UUID companyId) {
        if (playerId == null || companyId == null) return 0;
        List<String> targets = new ArrayList<>();
        for (OrderData order : new ArrayList<>(OrderState.ACCEPTED_ORDERS.values())) {
            if (playerId.equals(order.getAcceptedPlayer()) && companyId.equals(order.getOwnerCompany())) {
                targets.add(order.getOrderId());
            }
        }
        int released = 0;
        for (String orderId : targets) {
            OrderData order = OrderState.ACCEPTED_ORDERS.get(orderId);
            if (order == null) continue;
            ServerPlayer hauler = level.getServer().getPlayerList().getPlayer(playerId);
            if (hauler != null) {
                try {
                    ModPayloads.notifyRemoveWaypoint(orderId, hauler);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] releasePlayerCompanyOrders 删路径点失败 order={}", orderId, t);
                }
            }
            OrderPlayerBinding.unbind(orderId);
            try {
                ModPayloads.removeCargoBlocks(level, order);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] releasePlayerCompanyOrders 删货箱失败 order={}", orderId, t);
            }
            if (abandonOrder(orderId, level) != null) released++;
        }
        if (released > 0) {
            OrderState.schedulePendingBroadcastImmediate(level);
            OrderState.LOGGER.info("[CargoDispatch] 玩家 {} 离开公司 {}，{} 在途单回公司池", playerId, companyId, released);
        }
        return released;
    }

    // ==================== 公司解散：删除全部公司订单 ====================

    static int deleteCompanyOrders(ServerLevel level, UUID companyId) {
        if (companyId == null) return 0;
        int removed = 0;
        List<String> pendingIds = new ArrayList<>();
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            if (companyId.equals(order.getOwnerCompany())) pendingIds.add(order.getOrderId());
        }
        for (String id : pendingIds) {
            if (OrderState.removePendingOrder(id) != null) removed++;
        }
        List<OrderData> acceptedSnap = new ArrayList<>();
        for (OrderData order : new ArrayList<>(OrderState.ACCEPTED_ORDERS.values())) {
            if (companyId.equals(order.getOwnerCompany())) acceptedSnap.add(order);
        }
        for (OrderData order : acceptedSnap) {
            UUID haulerId = order.getAcceptedPlayer();
            if (haulerId != null) {
                ServerPlayer hauler = level.getServer().getPlayerList().getPlayer(haulerId);
                if (hauler != null) {
                    try {
                        ModPayloads.notifyRemoveWaypoint(order.getOrderId(), hauler);
                    } catch (Throwable t) {
                        OrderState.LOGGER.error("[CargoDispatch] deleteCompanyOrders 删路径点失败 order={}", order.getOrderId(), t);
                    }
                }
            }
            OrderPlayerBinding.unbind(order.getOrderId());
            try {
                ModPayloads.removeCargoBlocks(level, order);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] deleteCompanyOrders 删货箱失败 order={}", order.getOrderId(), t);
            }
            if (OrderState.removeAcceptedOrder(order.getOrderId()) != null) {
                order.setStatus(OrderData.Status.CANCELLED);
                removed++;
            }
        }
        if (removed > 0) {
            OrderState.markDirty(level);
            OrderState.schedulePendingBroadcastImmediate(level);
            OrderState.LOGGER.info("[CargoDispatch] 公司 {} 解散，删除 {} 公司单", companyId, removed);
        }
        return removed;
    }
}
