package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订单广播者（包私有，SRP：负责把「按玩家过滤后的订单列表数据包」推给客户端）。
 *
 * <p>与 {@link OrderNotifier} 的分工：Notifier 发音效/聊天，本类发列表数据包。
 * 一份 PENDING 快照 + 每玩家 NotifyProfile 复用，复杂度 O(PENDING + players)。</p>
 */
final class OrderBroadcaster {

    private OrderBroadcaster() {}

    /** 当前 PENDING 共享只读快照（1 次拷贝本轮复用） */
    private static List<OrderData> takeSnapshot() {
        if (OrderState.PENDING_ORDERS.isEmpty()) return List.of();
        return new ArrayList<>(OrderState.PENDING_ORDERS.values());
    }

    /** 从快照按 profile 过滤出 OrderEntry */
    private static List<SyncOrdersPayload.OrderEntry> filteredFromSnapshot(
            List<OrderData> snapshot, OrderNotifier.NotifyProfile profile) {
        if (snapshot == null || snapshot.isEmpty() || profile == null) return List.of();
        List<SyncOrdersPayload.OrderEntry> out = new ArrayList<>(Math.min(16, snapshot.size()));
        for (OrderData order : snapshot) {
            if (profile.canSeeOrder(order)) out.add(SyncOrdersPayload.OrderEntry.from(order));
        }
        return out.isEmpty() ? List.of() : out;
    }

    /** 按 isOrderVisibleToPlayer 构造某玩家可见条目（连接/断站精确口径） */
    static List<SyncOrdersPayload.OrderEntry> filteredForPlayer(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return List.of();
        List<OrderData> list = new ArrayList<>(OrderState.PENDING_ORDERS.size());
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            if (OrderQueries.isOrderVisibleToPlayer(level, playerId, order)) list.add(order);
        }
        if (list.isEmpty()) return List.of();
        return list.stream().map(SyncOrdersPayload.OrderEntry::from).toList();
    }

    /** 立刻给单玩家推一次过滤后的最新列表（断站/站被破坏时调用） */
    static void pushToPlayer(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return;
        try {
            List<SyncOrdersPayload.OrderEntry> filtered = filteredForPlayer(level, playerId);
            long nextRefresh = OrderState.lastRefreshTime + OrderState.nextRefreshTicks;
            player.connection.send(new SyncOrdersPayload(filtered, nextRefresh));
        } catch (Throwable t) {
            OrderState.LOGGER.error("[CargoDispatch] pushToPlayer 失败 player={}", player.getName().getString(), t);
        }
    }

    /** 向所有在线玩家广播各自过滤后的列表 */
    static void broadcast(ServerLevel level) {
        if (level == null) return;
        if (level.getServer().getPlayerCount() <= 0) return;

        List<OrderData> snapshot = takeSnapshot();
        LinkageManager linkageMgr = LinkageManager.get(level);
        Map<UUID, OrderNotifier.NotifyProfile> profiles = OrderNotifier.buildProfiles(level, linkageMgr);
        if (profiles.isEmpty()) return;

        int totalSynced = 0;
        long nextRefresh = OrderState.lastRefreshTime + OrderState.nextRefreshTicks;
        for (OrderNotifier.NotifyProfile profile : profiles.values()) {
            ServerPlayer player = profile.player();
            if (player == null) continue;
            try {
                List<SyncOrdersPayload.OrderEntry> filtered = filteredFromSnapshot(snapshot, profile);
                player.connection.send(new SyncOrdersPayload(filtered, nextRefresh));
                totalSynced++;
            } catch (Exception e) {
                OrderState.LOGGER.error("[CargoDispatch] 广播给玩家 {} 失败", player.getName().getString(), e);
            }
        }
        if (OrderState.LOGGER.isDebugEnabled()) {
            OrderState.LOGGER.debug("[CargoDispatch] broadcast(snapshot={})：向 {}/{} 玩家推送过滤列表",
                    snapshot.size(), totalSynced, level.getServer().getPlayerCount());
        }
    }
}
