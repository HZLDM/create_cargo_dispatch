package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 订单只读查询（包私有，SRP：不修改任何状态）。
 *
 * <p>集中三类查询：① 按池/类型/站点取订单；② 同站结构判定（XZ20/Y8）；
 * ③ 玩家可见性 / 属主访问校验；④ 站点真实存在性（含 fallback 扫描）。</p>
 *
 * <h3>同站口径</h3>
 * 订单位置（站本体/检测器/生成器）与连接位置属同一结构：类型一致 + XZ≤20、Y≤8。
 * 跨类型绝不是同站，避免把相邻同类型站错误合并。
 */
final class OrderQueries {

    private OrderQueries() {}

    // ==================== 基础读取 ====================

    @Nullable
    static OrderData getOrder(String orderId) {
        if (orderId == null) return null;
        OrderData order = OrderState.ACCEPTED_ORDERS.get(orderId);
        return order != null ? order : OrderState.PENDING_ORDERS.get(orderId);
    }

    static List<OrderData> getPendingOrders() {
        return new ArrayList<>(OrderState.PENDING_ORDERS.values());
    }

    static List<OrderData> getPendingOrders(StationType stationType) {
        if (stationType == StationType.GENERIC) return getPendingOrders();
        List<OrderData> result = new ArrayList<>();
        for (OrderData order : OrderState.PENDING_ORDERS.values()) {
            if (order.getStationType() == stationType) result.add(order);
        }
        return result;
    }

    static List<OrderData> getAllAcceptedOrders() {
        return new ArrayList<>(OrderState.ACCEPTED_ORDERS.values());
    }

    // ==================== 已接单查询 ====================

    static List<OrderData> getAcceptedOrdersByPlayer(UUID playerId) {
        if (playerId == null) return List.of();
        List<OrderData> result = new ArrayList<>();
        for (OrderData order : OrderState.ACCEPTED_ORDERS.values()) {
            if (playerId.equals(order.getAcceptedPlayer())) result.add(order);
        }
        return result;
    }

    /** 自己接的 + 同公司任意成员接的 */
    static List<OrderData> getAcceptedOrdersByPlayer(ServerLevel level, UUID playerId) {
        if (playerId == null) return List.of();
        UUID companyId = null;
        try {
            companyId = com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                    .getCompanyIdOfPlayer(playerId);
        } catch (Throwable t) {
            OrderState.LOGGER.error("[CargoDispatch] 查询玩家公司失败，活跃订单降级仅自己", t);
        }
        List<OrderData> result = new ArrayList<>();
        for (OrderData order : OrderState.ACCEPTED_ORDERS.values()) {
            if (playerId.equals(order.getAcceptedPlayer())) {
                result.add(order);
            } else if (companyId != null && companyId.equals(order.getOwnerCompany())) {
                result.add(order);
            }
        }
        return result;
    }

    // ==================== 按具体站点取 PENDING ====================

    /** 某具体站作为起点发出的 PENDING（每站点独立发单） */
    static List<OrderData> getPendingOrdersForStation(@Nullable StationType stationType, BlockPos stationPos) {
        if (stationPos == null) return List.of();
        List<OrderData> all = getPendingOrders();
        if (all.isEmpty()) return List.of();
        List<OrderData> result = new ArrayList<>(Math.min(16, all.size()));
        for (OrderData order : all) {
            if (isOrderStartBelongsToStation(order, stationType, stationPos)) result.add(order);
        }
        return result;
    }

    /** 「本站发出 + 属主可见」双重过滤 */
    static List<OrderData> getPendingOrdersForStation(ServerLevel level, UUID viewerId,
                                                       @Nullable StationType stationType, BlockPos stationPos) {
        List<OrderData> stationOrders = getPendingOrdersForStation(stationType, stationPos);
        if (stationOrders.isEmpty()) return List.of();
        List<OrderData> result = new ArrayList<>(Math.min(16, stationOrders.size()));
        for (OrderData order : stationOrders) {
            if (isOrderAccessibleAtStation(level, viewerId, order)) result.add(order);
        }
        return result;
    }

    // ==================== 同站结构判定 ====================

    static boolean isSameStation(BlockPos aPos, StationType aType, BlockPos bPos, StationType bType) {
        if (aPos == null || bPos == null || aType == null || bType == null) return false;
        if (aType != bType) return false;
        return isSameStationCompat(aPos, bPos);
    }

    /** 类型已保证一致时的结构距离判定（XZ≤20，Y≤8） */
    static boolean isSameStationCompat(BlockPos aPos, BlockPos bPos) {
        if (aPos == null || bPos == null) return false;
        if (aPos.equals(bPos)) return true;
        int dx = Math.abs(aPos.getX() - bPos.getX());
        int dy = Math.abs(aPos.getY() - bPos.getY());
        int dz = Math.abs(aPos.getZ() - bPos.getZ());
        return dx <= 20 && dy <= 8 && dz <= 20;
    }

    static boolean isSameStationTyped(BlockPos aPos, StationType aType, BlockPos bPos, StationType bType) {
        return isSameStation(aPos, aType, bPos, bType);
    }

    /** 订单起点是否就是当前站（类型匹配 + 同结构距离） */
    static boolean isOrderStartBelongsToStation(@Nullable OrderData order,
                                                 @Nullable StationType stationType,
                                                 @Nullable BlockPos stationPos) {
        if (order == null || stationPos == null) return false;
        StationType orderStartType = order.getStationType();
        BlockPos orderStart = order.getStartPos();
        if (orderStart == null || orderStartType == null) return false;
        if (stationType != null && stationType != StationType.GENERIC) {
            if (!(orderStartType == stationType || orderStartType == StationType.GENERIC)) return false;
        }
        return isSameStation(orderStart, orderStartType, stationPos, stationType);
    }

    // ==================== 属主访问 / 可见性 ====================

    /** 现场 GUI 属主校验：公司=成员可见；个人=仅属主；无属主旧单=站在本站即可见 */
    static boolean isOrderAccessibleAtStation(ServerLevel level, UUID viewerId, OrderData order) {
        if (order == null || viewerId == null) return false;
        UUID companyId = order.getOwnerCompany();
        if (companyId != null) {
            try {
                return com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                        .isMember(companyId, viewerId);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] isOrderAccessibleAtStation 查询成员失败，保守拒绝", t);
                return false;
            }
        }
        UUID ownerPlayer = order.getOwnerPlayer();
        return ownerPlayer == null || ownerPlayer.equals(viewerId);
    }

    /** PENDING 是否对玩家可见（通知/广播过滤） */
    static boolean isOrderVisibleToPlayer(ServerLevel level, UUID playerId, OrderData order) {
        if (level == null || playerId == null || order == null) return false;
        UUID companyOwner = order.getOwnerCompany();
        if (companyOwner != null) {
            try {
                return com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                        .isMember(companyOwner, playerId);
            } catch (Throwable t) {
                OrderState.LOGGER.error("[CargoDispatch] isOrderVisibleToPlayer 查询成员失败，保守拒绝", t);
                return false;
            }
        }
        UUID playerOwner = order.getOwnerPlayer();
        if (playerOwner != null) return playerOwner.equals(playerId);
        // 无属主旧单：按已连接站过滤
        LinkageManager lm = LinkageManager.get(level);
        List<LinkageManager.ConnectedStation> connected = lm.getConnectedStations(playerId);
        if (connected == null || connected.isEmpty()) return false;
        for (LinkageManager.ConnectedStation conn : connected) {
            if (conn == null || conn.type() == null || conn.pos() == null) continue;
            if (order.getStartPos() != null
                    && isSameStation(order.getStartPos(), order.getStationType(), conn.pos(), conn.type())) {
                return true;
            }
            if (order.getTargetPos() != null
                    && isSameStation(order.getTargetPos(), order.getTargetStationType(), conn.pos(), conn.type())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 站点真实存在性 ====================

    /**
     * 指定位置+类型是否真实对应一个站：chunk 未加载保守通过；单点 BE OK 通过；
     * 单点缺失则 XZ20/Y8 fallback 扫描最近同结构，找到即通过。
     */
    static boolean stationBlockExistsWithFallback(ServerLevel level, StationType expectType, BlockPos pos) {
        if (level == null || expectType == null || pos == null) return false;
        if (!level.isLoaded(pos)) return true; // chunk 未加载，保守通过
        StationLocationStore store = StationLocationStore.get(level);
        if (store.stationBlockEntityExists(level, expectType, pos)) return true;

        final int rangeXZ = 20, rangeY = 8;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final boolean allowGeneric = expectType == StationType.GENERIC;
        for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
            for (int dy = -rangeY; dy <= rangeY; dy++) {
                for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isLoaded(cursor)) continue;
                    BlockEntity be = level.getBlockEntity(cursor);
                    StationType t2 = null;
                    if (be instanceof CargoStationBlockEntity s) t2 = s.getStationType();
                    else if (be instanceof CargoDetectorBlockEntity d) t2 = d.getStationType();
                    else if (be instanceof CargoGeneratorBlockEntity g) t2 = g.getStationType();
                    else continue;
                    if (!allowGeneric && t2 != expectType
                            && t2 != StationType.GENERIC && expectType != StationType.GENERIC) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /** 接单前：起/终点站都真实存在（chunk 未加载保守通过） */
    static boolean sourceAndTargetStationsExistIfLoaded(ServerLevel level, OrderData order) {
        if (level == null || order == null) return false;
        BlockPos sp = order.getStartPos();
        if (sp == null || !stationBlockExistsWithFallback(level, order.getStationType(), sp)) return false;
        BlockPos tp = order.getTargetPos();
        return tp != null && stationBlockExistsWithFallback(level, order.getTargetStationType(), tp);
    }

    /** @deprecated 兼容：等价于 sourceAndTarget */
    @Deprecated
    static boolean targetStationExistsIfLoaded(ServerLevel level, OrderData order) {
        return sourceAndTargetStationsExistIfLoaded(level, order);
    }

    // ==================== 按货箱位置反查订单 ====================

    /** 通过货箱位置找订单ID（先查 CargoManager，再回退遍历 ACCEPTED 的 startPos） */
    @Nullable
    static String findOrderIdByCargoPos(BlockPos cargoPos) {
        if (cargoPos == null) return null;
        CargoData cargoData = CargoManager.query(cargoPos);
        if (cargoData != null && cargoData.getOrderId() != null) return cargoData.getOrderId();
        for (OrderData order : OrderState.ACCEPTED_ORDERS.values()) {
            if (cargoPos.equals(order.getStartPos())) return order.getOrderId();
        }
        return null;
    }

    // ==================== 刷新时间读取 ====================
    static long getRemainingTicks(long currentGameTime) {
        long elapsed = currentGameTime - OrderState.lastRefreshTime;
        return Math.max(0, OrderState.nextRefreshTicks - elapsed);
    }

    static long getNextRefreshInterval() { return OrderState.nextRefreshTicks; }
    static long getLastRefreshTime() { return OrderState.lastRefreshTime; }
}
