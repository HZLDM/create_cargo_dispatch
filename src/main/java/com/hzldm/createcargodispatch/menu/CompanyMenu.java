package com.hzldm.createcargodispatch.menu;

import com.hzldm.createcargodispatch.client.ClientCompanyCache;
import com.hzldm.createcargodispatch.company.CompanyService;
import com.hzldm.createcargodispatch.network.CreateCompanyPayload;
import com.hzldm.createcargodispatch.network.InviteCompanyMemberPayload;
import com.hzldm.createcargodispatch.network.JoinCompanyPayload;
import com.hzldm.createcargodispatch.network.KickCompanyMemberPayload;
import com.hzldm.createcargodispatch.network.LeaveCompanyPayload;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * 联合运输菜单（背包「联合运输」标签页）
 *
 * 原理：
 *  - 不绑定方块，状态完全来自服务端 SyncCompanyPayload 整表同步
 *  - 服务端构造时推一次最新状态（打开菜单的网络处理器也会先推一次，双保险）
 *  - 客户端动作只负责发包，服务端 CompanyService 统一鉴权，客户端显示不作为安全边界
 */
public class CompanyMenu extends AbstractContainerMenu {

    public CompanyMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.COMPANY.get(), containerId);
        if (playerInventory.player instanceof ServerPlayer serverPlayer) {
            CompanyService.sendSync(serverPlayer);
        }
    }

    // ============== 客户端数据读取（供 Screen 渲染） ==============

    public boolean isInCompany() {
        return ClientCompanyCache.isInCompany();
    }

    public String getCompanyName() {
        return ClientCompanyCache.getCompanyName();
    }

    public boolean isCreator() {
        return ClientCompanyCache.isCreator();
    }

    public List<SyncCompanyPayload.MemberEntry> getMembers() {
        return ClientCompanyCache.getMembers();
    }

    public List<SyncCompanyPayload.JoinableEntry> getJoinables() {
        return ClientCompanyCache.getJoinables();
    }

    public long getBalance() {
        return ClientCompanyCache.getBalance();
    }

    public int getReputation() {
        return ClientCompanyCache.getReputation();
    }

    public int getCompanyLevel() {
        return ClientCompanyCache.getCompanyLevel();
    }

    public int getLinkSlots() {
        return ClientCompanyCache.getLinkSlots();
    }

    public long getNextUpgradeCost() {
        return ClientCompanyCache.getNextUpgradeCost();
    }

    // ============== 客户端动作 ==============

    /** 请求提升公司等级（服务端校验创建者/余额/满级并扣费） */
    public void upgradeLevel() {
        PacketDistributor.sendToServer(new com.hzldm.createcargodispatch.network.UpgradeCompanyLevelPayload());
    }

    public void createCompany(String name) {
        if (name != null && !name.isBlank()) {
            PacketDistributor.sendToServer(new CreateCompanyPayload(name));
        }
    }

    public void joinCompany(UUID companyId) {
        if (companyId != null) {
            PacketDistributor.sendToServer(new JoinCompanyPayload(companyId));
        }
    }

    public void leaveCompany() {
        PacketDistributor.sendToServer(new LeaveCompanyPayload());
    }

    public void kickMember(UUID targetId) {
        if (targetId != null) {
            PacketDistributor.sendToServer(new KickCompanyMemberPayload(targetId));
        }
    }

    public void invite(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            PacketDistributor.sendToServer(new InviteCompanyMemberPayload(playerName));
        }
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
