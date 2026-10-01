package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单持久化存储（SavedData）
 *
 * 原理：
 *  - 使用 SavedData 机制把 PENDING_ORDERS 和 ACCEPTED_ORDERS 持久化到维度数据文件
 *  - 服务器重启后从磁盘恢复，玩家退出游戏不会丢失订单
 *  - lastRefreshTime 和 nextRefreshTicks 也一并保存，保证刷新周期连续
 *
 * 存储结构：
 *  - Pending: List<OrderData NBT>
 *  - Accepted: List<OrderData NBT>
 *  - LastRefreshTime: long
 *  - NextRefreshTicks: long
 */
public class OrderStore extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-OrderStore");
    private static final String DATA_NAME = "create_cargo_dispatch_orders";

    public OrderStore() {
    }

    /** 从 NBT 加载 */
    public static OrderStore load(CompoundTag tag, HolderLookup.Provider registries) {
        OrderStore store = new OrderStore();
        long lastRefresh = tag.getLong("LastRefreshTime");
        OrderManager.setLastRefreshTime(lastRefresh);

        // 加载下次刷新间隔（旧存档没有则 0，由 OrderManager.seed 随机生成）
        long nextTicks = tag.getLong("NextRefreshTicks");
        if (nextTicks > 0) {
            OrderManager.setNextRefreshTicks(nextTicks);
        }

        // 加载待接单订单
        ListTag pendingList = tag.getList("Pending", 10);
        for (int i = 0; i < pendingList.size(); i++) {
            OrderData order = new OrderData();
            order.load(pendingList.getCompound(i), registries);
            OrderManager.restorePendingOrder(order);
        }

        // 加载已接单订单
        ListTag acceptedList = tag.getList("Accepted", 10);
        for (int i = 0; i < acceptedList.size(); i++) {
            OrderData order = new OrderData();
            order.load(acceptedList.getCompound(i), registries);
            OrderManager.restoreAcceptedOrder(order);
        }

        LOGGER.info("[CargoDispatch] 加载订单存储：{} 个待接单，{} 个已接单，刷新时间 = {}，下次间隔 = {}",
                pendingList.size(), acceptedList.size(), lastRefresh, nextTicks);
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("LastRefreshTime", OrderManager.getLastRefreshTime());
        tag.putLong("NextRefreshTicks", OrderManager.getNextRefreshInterval());

        // 保存待接单订单
        ListTag pendingList = new ListTag();
        for (OrderData order : OrderManager.getPendingOrders()) {
            pendingList.add(order.save(registries));
        }
        tag.put("Pending", pendingList);

        // 保存已接单订单（包含所有玩家的活跃订单）
        ListTag acceptedList = new ListTag();
        for (OrderData order : OrderManager.getAllAcceptedOrders()) {
            acceptedList.add(order.save(registries));
        }
        tag.put("Accepted", acceptedList);

        return tag;
    }

    /** 获取订单存储（不存在则创建） */
    public static OrderStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OrderStore::new, OrderStore::load),
                DATA_NAME
        );
    }

    /** 标记为已修改（供 OrderManager 调用触发保存） */
    public static void markDirty(ServerLevel level) {
        OrderStore store = get(level);
        store.setDirty();
    }
}
