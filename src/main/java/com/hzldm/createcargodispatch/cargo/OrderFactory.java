package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.company.CompanyStore;
import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单工厂（包私有，SRP：负责订单的随机刷新、按需生成、尺寸变更重算）。
 *
 * <p>本类只决定「在什么条件下、为何站、生成什么订单」；
 * 奖励/数量构造委托 {@link RewardCalculator}，通知委托 {@link OrderNotifier}，
 * 状态读写走 {@link OrderState}，死站清理委托 {@link OrderJanitor}。</p>
 */
final class OrderFactory {

    private OrderFactory() {}

    // ==================== 实时随机刷新 ====================

    static void tryRefresh(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime - OrderState.lastRefreshTime < OrderState.nextRefreshTicks) return;
        OrderState.lastRefreshTime = gameTime;
        OrderState.nextRefreshTicks = OrderState.randomRefreshInterval();

        OrderJanitor.pruneDeadStationOrders(level);

        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        LinkageManager linkageMgr = LinkageManager.get(level);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 公司维度候选：每「在线 + 至少连 2 类型」的公司入选一次（公司等概率）
        LinkedHashMap<UUID, ServerPlayer> companyReps = new LinkedHashMap<>();
        CompanyStore companyStore;
        try {
            companyStore = CompanyStore.get(level);
        } catch (Throwable t) {
            OrderState.LOGGER.error("[CargoDispatch] 实时刷新读取公司存储失败", t);
            companyStore = null;
        }
        for (ServerPlayer p : players) {
            UUID companyId = companyStore != null ? companyStore.getCompanyIdOfPlayer(p.getUUID()) : null;
            if (companyId == null || companyReps.containsKey(companyId)) continue;
            long validTypes = linkageMgr.getConnectedTypes(p.getUUID()).stream()
                    .filter(t -> t != StationType.GENERIC).count();
            if (validTypes >= 2) companyReps.put(companyId, p);
        }
        if (companyReps.isEmpty()) {
            return;
        }
        List<UUID> companyIds = new ArrayList<>(companyReps.keySet());
        UUID chosenCompanyId = companyIds.get(rng.nextInt(companyIds.size()));
        ServerPlayer player = companyReps.get(chosenCompanyId);
        List<StationType> connectedTypes = linkageMgr.getConnectedTypes(player.getUUID());
        int maxPerStation = ModConfig.getMaxOrdersPerStation();

        // 每站点独立上限：收集未满的具体起点站
        List<LinkageManager.ConnectedStation> sourceStationCandidates = new ArrayList<>();
        for (StationType t : connectedTypes) {
            if (t == StationType.GENERIC) continue;
            for (LinkageManager.ConnectedStation s : linkageMgr.getConnectedStationsByType(player.getUUID(), t)) {
                if (OrderState.countOrdersBySourcePos(s.pos()) < maxPerStation) sourceStationCandidates.add(s);
            }
        }
        if (sourceStationCandidates.isEmpty()) return;

        LinkageManager.ConnectedStation source = sourceStationCandidates.get(rng.nextInt(sourceStationCandidates.size()));
        StationType sourceType = source.type();

        List<StationType> targetTypes = new ArrayList<>();
        for (StationType t : connectedTypes) {
            if (t != sourceType && t != StationType.GENERIC) targetTypes.add(t);
        }
        if (targetTypes.isEmpty()) return;
        StationType targetType = targetTypes.get(rng.nextInt(targetTypes.size()));

        List<LinkageManager.ConnectedStation> targetStations =
                linkageMgr.getConnectedStationsByType(player.getUUID(), targetType);
        if (targetStations.isEmpty()) return;
        LinkageManager.ConnectedStation target = targetStations.get(rng.nextInt(targetStations.size()));

        CargoDimensions dims = resolveOrderDims(level, source.pos(), sourceType);
        OrderData order = RewardCalculator.randomOrder(
                source.pos(), level.dimension().location(), sourceType, targetType,
                target.pos(), level.dimension().location(), gameTime, dims);
        order.setTargetStationId(resolveTargetStationId(level, target.pos()));
        applyOwner(level, player.getUUID(), order);
        OrderState.addPendingOrder(order);
        OrderState.markDirty(level);

        OrderNotifier.newOrder(level, order);
        OrderState.afterPendingChanged(level);

        OrderState.LOGGER.info("[CargoDispatch] 实时刷新：公司 {}（代表 {}）新订单 {}（{}[{}]→{}[{}]）",
                chosenCompanyId, player.getName().getString(), order.getOrderId(),
                sourceType.getId(), source.pos(), targetType.getId(), target.pos());
    }

    // ==================== 强制刷新（指令） ====================

    static int forceRefresh(ServerLevel level) {
        OrderState.LOGGER.info("[CargoDispatch] 强制刷新：清除 {} 个 PENDING", OrderState.PENDING_ORDERS.size());
        OrderState.clearPending();
        OrderState.lastRefreshTime = level.getGameTime();

        LinkageManager linkageMgr = LinkageManager.get(level);
        linkageMgr.clearAllGeneratedTypes();

        int totalGenerated = 0;
        OrderNotifier.beginBatch();
        try {
            LinkedHashMap<UUID, ServerPlayer> reps = new LinkedHashMap<>();
            CompanyStore store = CompanyStore.get(level);
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                UUID companyId = store.getCompanyIdOfPlayer(player.getUUID());
                if (companyId != null) reps.putIfAbsent(companyId, player);
            }
            for (ServerPlayer player : reps.values()) {
                List<StationType> types = linkageMgr.getConnectedTypes(player.getUUID());
                for (StationType type : types) {
                    if (type != StationType.GENERIC) {
                        int n = ensureOrders(level, player.getUUID(), type);
                        if (n >= 0) {
                            linkageMgr.markTypeGenerated(player.getUUID(), type);
                            totalGenerated += n;
                        }
                    }
                }
            }
        } finally {
            OrderNotifier.endBatch(level);
        }

        OrderState.markDirty(level);
        OrderState.afterPendingChanged(level);
        OrderState.LOGGER.info("[CargoDispatch] 强制刷新完成：本次生成 {} 个新订单", totalGenerated);
        return totalGenerated;
    }

    // ==================== 连接站点时确保订单 ====================

    static int ensureOrders(ServerLevel level, UUID playerId, StationType sourceType) {
        if (sourceType == StationType.GENERIC || playerId == null) return 0;

        LinkageManager linkageMgr = LinkageManager.get(level);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int maxPerStation = ModConfig.getMaxOrdersPerStation();

        List<LinkageManager.ConnectedStation> sourceStations =
                linkageMgr.getConnectedStationsByType(playerId, sourceType);
        if (sourceStations.isEmpty()) return 0;

        List<LinkageManager.ConnectedStation> availableSources = new ArrayList<>();
        for (LinkageManager.ConnectedStation s : sourceStations) {
            if (OrderState.countOrdersBySourcePos(s.pos()) < maxPerStation) availableSources.add(s);
        }
        if (availableSources.isEmpty()) return 0;

        List<StationType> connectedTypes = linkageMgr.getConnectedTypes(playerId);
        List<StationType> targetTypes = new ArrayList<>();
        for (StationType t : connectedTypes) {
            if (t != sourceType && t != StationType.GENERIC) targetTypes.add(t);
        }
        if (targetTypes.isEmpty()) return 0;

        StationType targetType = targetTypes.get(rng.nextInt(targetTypes.size()));
        List<LinkageManager.ConnectedStation> targetStations =
                linkageMgr.getConnectedStationsByType(playerId, targetType);
        if (targetStations.isEmpty()) return 0;

        LinkageManager.ConnectedStation source = availableSources.get(rng.nextInt(availableSources.size()));
        LinkageManager.ConnectedStation target = targetStations.get(rng.nextInt(targetStations.size()));

        long gameTime = level.getGameTime();
        CargoDimensions dims = resolveOrderDims(level, source.pos(), sourceType);
        OrderData order = RewardCalculator.randomOrder(
                source.pos(), level.dimension().location(), sourceType, targetType,
                target.pos(), level.dimension().location(), gameTime, dims);
        order.setTargetStationId(resolveTargetStationId(level, target.pos()));
        applyOwner(level, playerId, order);
        OrderState.addPendingOrder(order);

        OrderNotifier.newOrder(level, order);
        OrderState.afterPendingChanged(level);

        OrderState.LOGGER.info("[CargoDispatch] 玩家 {} 站点 {}({}) 生成 1 个订单（连接触发）",
                playerId, sourceType.getId(), source.pos());
        OrderState.markDirty(level);
        return 1;
    }

    // ==================== 指令为指定站生成订单 ====================

    @Nullable
    static OrderData generateOrderForStation(ServerLevel level, UUID playerId,
                                              BlockPos sourcePos, StationType sourceType) {
        if (level == null || playerId == null || sourcePos == null
                || sourceType == null || sourceType == StationType.GENERIC) {
            return null;
        }
        LinkageManager linkageMgr = LinkageManager.get(level);

        // 统一 startPos 口径：detector 优先
        BlockPos detectorPos = com.hzldm.createcargodispatch.network.ModPayloads
                .searchNearbyDetectorForOrderPos(level, sourcePos, sourceType);
        BlockPos finalStartPos = detectorPos != null ? detectorPos : sourcePos;

        List<StationType> targetTypes = new ArrayList<>();
        for (StationType t : linkageMgr.getConnectedTypes(playerId)) {
            if (t != sourceType && t != StationType.GENERIC) targetTypes.add(t);
        }
        if (targetTypes.isEmpty()) return null;

        int maxPerStation = ModConfig.getMaxOrdersPerStation();
        if (OrderState.countOrdersBySourcePos(finalStartPos) >= maxPerStation) return null;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StationType targetType = targetTypes.get(rng.nextInt(targetTypes.size()));
        List<LinkageManager.ConnectedStation> targetStations =
                linkageMgr.getConnectedStationsByType(playerId, targetType);
        if (targetStations.isEmpty()) return null;
        LinkageManager.ConnectedStation target = targetStations.get(rng.nextInt(targetStations.size()));

        long gameTime = level.getGameTime();
        BlockPos finalTargetPos = target.pos();
        try {
            BlockPos targetDetector = com.hzldm.createcargodispatch.network.ModPayloads
                    .searchNearbyDetectorForOrderPos(level, target.pos(), targetType);
            if (targetDetector != null) finalTargetPos = targetDetector;
        } catch (Throwable t) {
            // 回退原 pos
        }
        CargoDimensions dims = resolveOrderDims(level, finalStartPos, sourceType);
        OrderData order = RewardCalculator.randomOrder(
                finalStartPos.immutable(), level.dimension().location(), sourceType, targetType,
                finalTargetPos.immutable(), level.dimension().location(), gameTime, dims);
        order.setTargetStationId(resolveTargetStationId(level, finalTargetPos));
        applyOwner(level, playerId, order);
        OrderState.addPendingOrder(order);

        OrderNotifier.newOrder(level, order);
        OrderState.afterPendingChanged(level);
        OrderState.markDirty(level);

        OrderState.LOGGER.info("[CargoDispatch] 玩家 {} 在 {}({}→{}) 生成订单 {} → {}(→{})",
                playerId, sourceType.getId(), sourcePos, finalStartPos,
                order.getOrderId(), targetType.getId(), finalTargetPos);
        return order;
    }

    // ==================== 尺寸变更：原地刷新 PENDING ====================

    static int refreshPendingDimensions(ServerLevel level, BlockPos stationPos,
                                         StationType stationType, CargoDimensions newDims) {
        List<OrderData> stationOrders = OrderQueries.getPendingOrdersForStation(stationType, stationPos);
        if (stationOrders.isEmpty() || newDims == null) return 0;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int changed = 0;
        for (OrderData order : stationOrders) {
            order.setDimensions(newDims);

            StationType src = order.getStationType();
            Item orderItem = resolveItemById(order.getCargoItemId());
            int minCount = CargoBalance.minItems(src, newDims, orderItem);
            int maxCount = Math.max(minCount + 1, CargoBalance.maxItems(src, newDims, orderItem) + 1);
            order.setCargoCount(rng.nextInt(minCount, maxCount));

            double distance = 0.0;
            if (order.getStartPos() != null && order.getTargetPos() != null) {
                distance = Math.sqrt(order.getStartPos().distSqr(order.getTargetPos()));
            }
            order.setReward(RewardCalculator.computeReward(src, newDims, order.getCargoCount(), distance));
            changed++;
        }
        OrderState.markDirty(level);
        OrderState.afterPendingChanged(level);
        return changed;
    }

    // ==================== 私有 helper ====================

    /** 盖属主：触发玩家在公司 → 公司属主；否则个人属主；异常降级个人 */
    private static void applyOwner(ServerLevel level, UUID generatorPlayer, OrderData order) {
        if (generatorPlayer == null) return;
        try {
            UUID companyId = CompanyStore.get(level).getCompanyIdOfPlayer(generatorPlayer);
            if (companyId != null) order.setOwnerCompany(companyId);
            else order.setOwnerPlayer(generatorPlayer);
        } catch (Throwable t) {
            OrderState.LOGGER.error("[CargoDispatch] applyOwner 失败，降级个人属主 player={}", generatorPlayer, t);
            order.setOwnerPlayer(generatorPlayer);
        }
    }

    /** 目标站编号；异常返回空串（旧单类型宽松匹配） */
    private static String resolveTargetStationId(ServerLevel level, BlockPos targetPos) {
        try {
            String id = StationGroupHelper.resolveGroupIdAt(level, targetPos);
            return id != null ? id : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 始发站配置的货箱尺寸；找不到用默认 */
    private static CargoDimensions resolveOrderDims(ServerLevel level, BlockPos sourcePos, StationType sourceType) {
        CargoStationBlockEntity st = StationGroupHelper.findBoundStation(level, sourcePos, sourceType);
        return st != null ? st.getConfiguredDimensions() : CargoDimensions.DEFAULT;
    }

    /** 按 id 解析物品；空/非法返回 null */
    @Nullable
    private static Item resolveItemById(@Nullable String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        return rl != null ? BuiltInRegistries.ITEM.getOptional(rl).orElse(null) : null;
    }
}
