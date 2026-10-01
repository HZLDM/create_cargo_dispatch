package com.hzldm.createcargodispatch.cargo;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单-玩家绑定表（运行时单例，不持久化）
 *
 * 原理：
 *  - 接单时调用 bind(orderId, playerUuid) 记录哪个玩家接了哪个订单
 *  - 货物到达目标检测器时调用 getPlayer(orderId) 查询玩家 UUID
 *  - 用于通知接单玩家删除 Xaero 路径点、发放奖励等
 *
 * 设计说明：
 *  - 不持久化到 NBT/SavedData：服务器重启后玩家需重新接单，路径点由客户端重建
 *  - 接单玩家正常会看到路径点丢失，属于可接受行为
 *  - 使用 ConcurrentHashMap 保证线程安全
 */
public final class OrderPlayerBinding {

    private static final ConcurrentHashMap<String, UUID> BINDINGS = new ConcurrentHashMap<>();

    private OrderPlayerBinding() {
    }

    /** 绑定订单和玩家 */
    public static void bind(String orderId, UUID playerId) {
        if (orderId == null || playerId == null) return;
        BINDINGS.put(orderId, playerId);
    }

    /** 查询订单对应的玩家 UUID */
    public static UUID getPlayer(String orderId) {
        if (orderId == null) return null;
        return BINDINGS.get(orderId);
    }

    /** 订单完成时解绑 */
    public static void unbind(String orderId) {
        if (orderId != null) {
            BINDINGS.remove(orderId);
        }
    }

    /** 服务器停止时清理 */
    public static void clear() {
        BINDINGS.clear();
    }
}
