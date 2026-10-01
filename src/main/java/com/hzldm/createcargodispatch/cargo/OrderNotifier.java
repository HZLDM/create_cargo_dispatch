package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import com.hzldm.createcargodispatch.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订单通知者（包私有，SRP：负责新订单/超时取消的音效与聊天消息，含批量合并）。
 *
 * <p>与 {@link OrderBroadcaster} 的分工：本类只发「音效 + 聊天文案」，
 * Broadcaster 负责发「订单列表数据包」。</p>
 *
 * <h3>批量模式</h3>
 * {@code beginBatch → 多次生成（notify 只缓存）→ endBatch}，
 * 每个玩家一批最多听 1 次提示音、收 1 条汇总，避免轰炸。
 */
final class OrderNotifier {

    private OrderNotifier() {}

    // ==================== 批量模式开关 ====================

    static void beginBatch() {
        synchronized (OrderState.BATCH_NOTIFY_LOCK) {
            OrderState.inBatchNotification = true;
            OrderState.batchedOrdersByPlayer.clear();
            OrderState.batchedOrderCount = 0;
        }
    }

    static void endBatch(ServerLevel level) {
        if (level == null) return;
        final int totalNewOrders;
        final Map<UUID, List<OrderData>> snapshot;
        synchronized (OrderState.BATCH_NOTIFY_LOCK) {
            MinecraftServer server = level.getServer();
            if (server == null) {
                OrderState.inBatchNotification = false;
                OrderState.batchedOrdersByPlayer.clear();
                return;
            }
            snapshot = new HashMap<>(OrderState.batchedOrdersByPlayer);
            totalNewOrders = OrderState.batchedOrderCount;
            OrderState.inBatchNotification = false;
            OrderState.batchedOrdersByPlayer.clear();
            OrderState.batchedOrderCount = 0;
        }
        if (totalNewOrders <= 0) return;

        final boolean chatAllowed = ModConfig.isNewOrderChatEnabled();
        final boolean soundAllowed = ModConfig.isNewOrderSoundEnabled();
        if (!chatAllowed && !soundAllowed) return;

        LinkageManager linkageMgr = LinkageManager.get(level);
        Map<UUID, NotifyProfile> profiles = buildProfiles(level, linkageMgr);
        if (profiles.isEmpty()) return;

        int notifiedPlayers = 0;
        for (var e : snapshot.entrySet()) {
            NotifyProfile profile = profiles.get(e.getKey());
            if (profile == null) continue; // 离线残留
            ServerPlayer player = profile.player();
            List<OrderData> orders = new java.util.ArrayList<>(e.getValue().size());
            for (OrderData o : e.getValue()) {
                if (!profile.canSeeOrder(o)) continue;
                if (!profile.notificationEnabledFor(o.getStationType())) continue;
                orders.add(o);
            }
            if (orders.isEmpty()) continue;
            notifiedPlayers++;
            if (soundAllowed) {
                try {
                    player.playNotifySound(ModSounds.NEW_ORDER.value(), SoundSource.PLAYERS, 0.8f, 1.0F);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] 批量新订单音效失败 player={}", player.getName().getString(), t);
                }
            }
            if (chatAllowed) {
                try {
                    player.sendSystemMessage(buildBatchNewOrderMessage(level, orders));
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] 批量新订单消息失败 player={}", player.getName().getString(), t);
                }
            }
        }
        OrderState.LOGGER.info("[CargoDispatch] endBatch：批量新增 {} 单，通知 {} 玩家（每人 1 音 1 消息）",
                totalNewOrders, notifiedPlayers);
    }

    // ==================== 单条新订单通知 ====================

    static void newOrder(ServerLevel level, OrderData order) {
        if (level == null || order == null || order.getStartPos() == null) return;

        LinkageManager linkageMgr = LinkageManager.get(level);
        Map<UUID, NotifyProfile> profiles = buildProfiles(level, linkageMgr);
        if (profiles.isEmpty()) return;

        final StationType startType = order.getStationType();
        synchronized (OrderState.BATCH_NOTIFY_LOCK) {
            if (OrderState.inBatchNotification) {
                int matched = 0;
                for (NotifyProfile profile : profiles.values()) {
                    if (!profile.canSeeOrder(order)) continue;
                    OrderState.batchedOrdersByPlayer
                            .computeIfAbsent(profile.player().getUUID(), k -> new java.util.ArrayList<>(4)).add(order);
                    matched++;
                }
                OrderState.batchedOrderCount++;
                return;
            }
        }

        final boolean chatAllowed = ModConfig.isNewOrderChatEnabled();
        final boolean soundAllowed = ModConfig.isNewOrderSoundEnabled();
        if (!chatAllowed && !soundAllowed) return;

        List<ServerPlayer> players = new ArrayList<>(profiles.size());
        for (NotifyProfile profile : profiles.values()) {
            if (!profile.canSeeOrder(order)) continue;
            if (!profile.notificationEnabledFor(startType)) continue;
            players.add(profile.player());
        }
        if (players.isEmpty()) return;

        BlockPos startPos = order.getStartPos();
        String dimStr = order.getStartDimension() != null ? order.getStartDimension().toString() : "minecraft:overworld";
        String waypointName = "订单起点#" + order.getOrderId();
        String command = String.format("/createcargodispatch addwaypoint %d %d %d \"%s\" %s",
                startPos.getX(), startPos.getY(), startPos.getZ(), dimStr, waypointName);

        Component posComponent = Component.literal(
                        String.format("(%d, %d, %d)", startPos.getX(), startPos.getY(), startPos.getZ()))
                .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("create_cargo_dispatch.order.click_to_add_waypoint"))));

        String routeShort = Component.translatable(order.getStationType().getShortTranslationKey()).getString()
                + " → "
                + Component.translatable(order.getTargetStationType().getShortTranslationKey()).getString();
        String remainStr = OrderData.formatRemainingTime(order.getRemainingSeconds(level.getGameTime()));
        Component message = Component.translatable("create_cargo_dispatch.order.new_order_notify")
                .append(Component.literal("\n"))
                .append(Component.translatable("create_cargo_dispatch.order.order_route_and_pos_expire",
                        routeShort, order.getOrderId(), remainStr))
                .append(posComponent);

        OrderState.LOGGER.info("[CargoDispatch] 通知新订单：orderId={}, 起点类型={}, 过滤后玩家数={}",
                order.getOrderId(), startType.getId(), players.size());
        for (ServerPlayer player : players) {
            if (soundAllowed) {
                try {
                    player.playNotifySound(ModSounds.NEW_ORDER.value(), SoundSource.PLAYERS, 0.8f, 1.0f);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] notifyNewOrder 音效失败 player={},order={}",
                            player.getName().getString(), order.getOrderId(), t);
                }
            }
            if (chatAllowed) {
                try {
                    player.sendSystemMessage(message);
                } catch (Throwable t) {
                    OrderState.LOGGER.error("[CargoDispatch] notifyNewOrder 消息失败 player={}", player.getName().getString(), t);
                }
            }
        }
    }

    // ==================== 超时取消通知 ====================

    static void expired(ServerLevel level, List<OrderData> expiredOrders) {
        if (level == null || expiredOrders == null || expiredOrders.isEmpty()) return;

        final boolean chatAllowed = ModConfig.isNewOrderChatEnabled();
        final boolean soundAllowed = ModConfig.isNewOrderSoundEnabled();
        if (!chatAllowed && !soundAllowed) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;

        LinkageManager linkageMgr = LinkageManager.get(level);
        Map<UUID, NotifyProfile> profiles = buildProfiles(level, linkageMgr);
        if (profiles.isEmpty()) return;

        Map<UUID, List<OrderData>> byPlayer = new HashMap<>();
        for (OrderData order : expiredOrders) {
            if (order == null) continue;
            StationType startType = order.getStationType();
            for (NotifyProfile profile : profiles.values()) {
                if (!profile.canSeeOrder(order)) continue;
                if (!profile.notificationEnabledFor(startType)) continue;
                byPlayer.computeIfAbsent(profile.player().getUUID(), k -> new java.util.ArrayList<>(4)).add(order);
            }
        }
        if (byPlayer.isEmpty()) return;

        int notifiedPlayers = 0;
        for (var e : byPlayer.entrySet()) {
            NotifyProfile profile = profiles.get(e.getKey());
            if (profile == null) continue;
            ServerPlayer player = profile.player();
            if (player == null) continue;
            try {
                if (soundAllowed) player.playNotifySound(ModSounds.ORDER_TIMEOUT.value(), SoundSource.PLAYERS, 0.8f, 1.0F);
                if (chatAllowed) player.sendSystemMessage(buildExpiredBatchMessage(e.getValue()));
                notifiedPlayers++;
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] 超时通知失败 player={}", player.getName().getString(), t);
            }
        }
        OrderState.LOGGER.info("[CargoDispatch] notifyExpired：{} 条超时单 → {} 玩家（每人 1 音 1 消息）",
                expiredOrders.size(), notifiedPlayers);
    }

    // ==================== 汇总消息构造 ====================

    private static Component buildBatchNewOrderMessage(ServerLevel level, List<OrderData> orders) {
        long currentGameTime = level != null ? level.getGameTime() : 0L;
        net.minecraft.network.chat.MutableComponent msg =
                Component.translatable("create_cargo_dispatch.order.new_orders_batch", orders.size());
        for (OrderData order : orders) {
            BlockPos startPos = order.getStartPos();
            if (startPos == null) continue;
            String dimStr = order.getStartDimension() != null ? order.getStartDimension().toString() : "minecraft:overworld";
            String command = String.format("/createcargodispatch addwaypoint %d %d %d \"%s\" 订单起点#%s",
                    startPos.getX(), startPos.getY(), startPos.getZ(), dimStr, order.getOrderId());
            Component posComponent = Component.literal(String.format("(%d,%d,%d)",
                            startPos.getX(), startPos.getY(), startPos.getZ()))
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("create_cargo_dispatch.order.click_to_add_waypoint"))));
            String routeShort = Component.translatable(order.getStationType().getShortTranslationKey()).getString()
                    + " → "
                    + Component.translatable(order.getTargetStationType().getShortTranslationKey()).getString();
            String remainStr = OrderData.formatRemainingTime(order.getRemainingSeconds(currentGameTime));
            msg.append(Component.literal("\n"))
                    .append(Component.translatable("create_cargo_dispatch.order.order_route_and_pos_expire",
                            routeShort, order.getOrderId(), remainStr))
                    .append(posComponent);
        }
        return msg;
    }

    private static Component buildExpiredBatchMessage(List<OrderData> orders) {
        net.minecraft.network.chat.MutableComponent msg =
                Component.translatable("create_cargo_dispatch.order.expired_batch_title", orders.size());
        for (OrderData order : orders) {
            BlockPos startPos = order.getStartPos();
            if (startPos == null) continue;
            String routeShort = Component.translatable(order.getStationType().getShortTranslationKey()).getString()
                    + " → "
                    + Component.translatable(order.getTargetStationType().getShortTranslationKey()).getString();
            msg.append(Component.literal("\n"))
                    .append(Component.translatable("create_cargo_dispatch.order.expired_single",
                            routeShort, order.getOrderId(),
                            startPos.getX(), startPos.getY(), startPos.getZ()));
        }
        return msg;
    }

    // ==================== NotifyProfile ====================

    /** 单玩家通知判定预计算快照（一轮内 N 单复用） */
    record NotifyProfile(
            ServerPlayer player,
            java.util.Set<Long> connectedPositions,
            java.util.Set<StationType> notifyEnabledTypes,
            List<LinkageManager.ConnectedStation> connected,
            UUID companyId
    ) {
        boolean canSeeOrder(OrderData order) {
            if (order == null) return false;
            UUID companyOwner = order.getOwnerCompany();
            if (companyOwner != null) return companyOwner.equals(companyId);
            UUID playerOwner = order.getOwnerPlayer();
            if (playerOwner != null) return playerOwner.equals(player.getUUID());
            if (connected == null || connected.isEmpty()) return false;
            return matchesStation(order.getStartPos(), order.getStationType())
                    || matchesStation(order.getTargetPos(), order.getTargetStationType());
        }

        private boolean matchesStation(BlockPos pos, StationType type) {
            if (pos == null || type == null) return false;
            if (connectedPositions.contains(pos.asLong())) return true;
            for (LinkageManager.ConnectedStation s : connected) {
                if (s == null || s.pos() == null || s.type() != type) continue;
                int dx = Math.abs(s.pos().getX() - pos.getX());
                int dy = Math.abs(s.pos().getY() - pos.getY());
                int dz = Math.abs(s.pos().getZ() - pos.getZ());
                if (dx <= 20 && dy <= 8 && dz <= 20) return true;
            }
            return false;
        }

        boolean notificationEnabledFor(StationType startType) {
            return startType != null && notifyEnabledTypes.contains(startType);
        }
    }

    /** 构造在线玩家 NotifyProfile 映射（各通知/广播统一入口，每玩家只取一次连接/开关） */
    static Map<UUID, NotifyProfile> buildProfiles(ServerLevel level, LinkageManager linkageMgr) {
        if (level == null) return Map.of();
        MinecraftServer server = level.getServer();
        if (server == null) return Map.of();
        List<ServerPlayer> list = server.getPlayerList().getPlayers();
        if (list.isEmpty()) return Map.of();

        com.hzldm.createcargodispatch.company.CompanyStore companyStore;
        try {
            companyStore = com.hzldm.createcargodispatch.company.CompanyStore.get(level);
        } catch (Throwable t) {
            companyStore = null;
        }
        HashMap<UUID, NotifyProfile> map = new HashMap<>((int) (list.size() / 0.75F) + 1);
        for (ServerPlayer p : list) {
            UUID pid = p.getUUID();
            java.util.Set<Long> positions = linkageMgr != null
                    ? linkageMgr.getConnectedPositionsPacked(pid) : java.util.Collections.emptySet();
            java.util.Set<StationType> notifyTypes = linkageMgr != null
                    ? linkageMgr.getNotifyEnabledTypes(pid) : java.util.Collections.emptySet();
            List<LinkageManager.ConnectedStation> connected = linkageMgr != null
                    ? linkageMgr.getConnectedStations(pid) : java.util.List.of();
            UUID companyId = null;
            if (companyStore != null) {
                try {
                    companyId = companyStore.getCompanyIdOfPlayer(pid);
                } catch (Throwable ignored) {
                }
            }
            map.put(pid, new NotifyProfile(p, positions, notifyTypes, connected, companyId));
        }
        return map;
    }
}
