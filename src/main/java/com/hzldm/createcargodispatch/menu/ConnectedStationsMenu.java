package com.hzldm.createcargodispatch.menu;

import com.hzldm.createcargodispatch.client.ClientCargoCache;
import com.hzldm.createcargodispatch.network.SyncLinkagesPayload;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 已连接站点菜单（背包连接页）
 *
 * 原理：
 *  - 不绑定 BlockEntity，数据从 LinkageManager / ClientCargoCache 读取
 *  - 服务端构造时同步已连接站点列表给客户端
 *  - 客户端通过 ClientCargoCache.getLinkages() 渲染
 *  - 支持断开连接（删除单个站点）
 */
public class ConnectedStationsMenu extends AbstractContainerMenu {

    public ConnectedStationsMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.CONNECTED_STATIONS.get(), containerId);
        // 服务端：同步已连接站点列表给客户端
        if (playerInventory.player instanceof ServerPlayer serverPlayer) {
            List<SyncLinkagesPayload.LinkageEntry> linkages =
                    com.hzldm.createcargodispatch.cargo.LinkageManager.get(serverPlayer.serverLevel())
                            .getConnectedStations(serverPlayer.getUUID()).stream()
                            .map(s -> new SyncLinkagesPayload.LinkageEntry(
                                    s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), s.notifyEnabled()))
                            .toList();
            PacketDistributor.sendToPlayer(serverPlayer, new SyncLinkagesPayload(linkages));
        }
    }

    /** 获取已连接站点列表（客户端从缓存读取） */
    public List<SyncLinkagesPayload.LinkageEntry> getLinkages() {
        return ClientCargoCache.getLinkages();
    }

    /** 客户端点击断开连接：发送断开请求 */
    public void disconnectStation(int index) {
        List<SyncLinkagesPayload.LinkageEntry> list = getLinkages();
        if (index < 0 || index >= list.size()) {
            return;
        }
        SyncLinkagesPayload.LinkageEntry entry = list.get(index);
        com.hzldm.createcargodispatch.network.DisconnectStationPayload payload =
                new com.hzldm.createcargodispatch.network.DisconnectStationPayload(
                        entry.sourceX(), entry.sourceY(), entry.sourceZ());
        PacketDistributor.sendToServer(payload);
    }

    /** 需求4：客户端点击单站 🔔/🔕 按钮 → 发送 ToggleStationNotifyPayload */
    public void toggleStationNotify(int index, boolean enabled) {
        List<SyncLinkagesPayload.LinkageEntry> list = getLinkages();
        if (index < 0 || index >= list.size()) return;
        SyncLinkagesPayload.LinkageEntry entry = list.get(index);
        com.hzldm.createcargodispatch.network.ToggleStationNotifyPayload payload =
                new com.hzldm.createcargodispatch.network.ToggleStationNotifyPayload(
                        entry.sourceX(), entry.sourceY(), entry.sourceZ(), enabled);
        PacketDistributor.sendToServer(payload);
    }

    /** 需求4：客户端点击底部"一键全部开/关" → 发送 ToggleAllStationsNotifyPayload */
    public void toggleAllStationsNotify(boolean enabled) {
        PacketDistributor.sendToServer(
                new com.hzldm.createcargodispatch.network.ToggleAllStationsNotifyPayload(enabled));
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
