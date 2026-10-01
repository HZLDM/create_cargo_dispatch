package com.hzldm.createcargodispatch.menu;

import com.hzldm.createcargodispatch.client.ClientCargoCache;
import com.hzldm.createcargodispatch.network.AbandonOrderPayload;
import com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 玩家订单菜单（背包订单页）
 *
 * 原理：
 *  - 不绑定 BlockEntity，数据完全从 OrderManager 或 ClientCargoCache 读取
 *  - 服务端构造时发送 SyncActiveOrdersPayload 同步玩家活跃订单
 *  - 客户端通过 ClientCargoCache.getActiveOrders() 实时渲染
 *  - 放弃订单通过 abandonOrder() 发送 AbandonOrderPayload
 *  - 已连接站点列表在独立的 ConnectedStationsMenu 中处理
 */
public class PlayerOrdersMenu extends AbstractContainerMenu {

    public PlayerOrdersMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.PLAYER_ORDERS.get(), containerId);
        // 服务端：发送活跃订单给客户端（联合运输成员含同公司其他成员的配送单）
        if (playerInventory.player instanceof ServerPlayer serverPlayer) {
            List<SyncActiveOrdersPayload.ActiveOrderEntry> orders =
                    com.hzldm.createcargodispatch.cargo.OrderManager.getAcceptedOrdersByPlayer(
                                    serverPlayer.serverLevel(), serverPlayer.getUUID())
                            .stream()
                            .map(SyncActiveOrdersPayload.ActiveOrderEntry::from)
                            .toList();
            PacketDistributor.sendToPlayer(serverPlayer, new SyncActiveOrdersPayload(orders));
        }
    }

    /** 获取当前活跃订单（客户端从缓存读取） */
    public List<SyncActiveOrdersPayload.ActiveOrderEntry> getActiveOrders() {
        return ClientCargoCache.getActiveOrders();
    }

    /** 客户端点击放弃订单：发送放弃请求 */
    public void abandonOrder(int orderIndex) {
        List<SyncActiveOrdersPayload.ActiveOrderEntry> list = getActiveOrders();
        if (orderIndex < 0 || orderIndex >= list.size()) {
            return;
        }
        AbandonOrderPayload payload = new AbandonOrderPayload(list.get(orderIndex).orderId());
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
