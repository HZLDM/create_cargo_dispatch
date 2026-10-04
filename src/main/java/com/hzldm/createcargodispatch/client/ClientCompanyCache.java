package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload.JoinableEntry;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload.MemberEntry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端联合运输状态缓存
 *
 * 原理：
 *  - 服务端任何公司状态变更都重发整包，这里整表替换（最终一致，无增量合并漏洞）
 *  - 列表用 CopyOnWriteArrayList：渲染线程遍历、网络线程写入互不干扰
 *  - 标量字段 volatile 保证可见性
 *  - 与 ClientCargoCache 平级独立，避免订单缓存继续膨胀
 */
public final class ClientCompanyCache {

    private static volatile boolean inCompany = false;
    private static volatile UUID companyId = null;
    private static volatile String companyName = "";
    private static volatile UUID creatorId = null;
    private static volatile long balance = 0L;
    private static volatile int reputation = 0;
    private static volatile int companyLevel = 1;
    private static volatile int linkSlots = 0;
    private static volatile long nextUpgradeCost = SyncCompanyPayload.COST_MAX_LEVEL;

    private static volatile List<MemberEntry> members = new CopyOnWriteArrayList<>();
    private static volatile List<JoinableEntry> joinables = new CopyOnWriteArrayList<>();

    private ClientCompanyCache() {
    }

    /** 整表替换服务端同步状态 */
    public static void update(SyncCompanyPayload payload) {
        if (payload == null) return;
        inCompany = payload.inCompany();
        companyId = payload.companyId();
        companyName = payload.companyName() == null ? "" : payload.companyName();
        creatorId = payload.creatorId();
        balance = payload.balance();
        reputation = payload.reputation();
        companyLevel = payload.companyLevel() <= 0 ? 1 : payload.companyLevel();
        linkSlots = payload.linkSlots();
        nextUpgradeCost = payload.nextUpgradeCost();
        members = new CopyOnWriteArrayList<>(payload.members() == null ? List.of() : payload.members());
        joinables = new CopyOnWriteArrayList<>(payload.joinables() == null ? List.of() : payload.joinables());
    }

    public static boolean isInCompany() {
        return inCompany;
    }

    public static UUID getCompanyId() {
        return companyId;
    }

    public static String getCompanyName() {
        return companyName;
    }

    public static UUID getCreatorId() {
        return creatorId;
    }

    /** 当前客户端玩家是否为创建者（决定踢人/解散/邀请控件是否显示） */
    @OnlyIn(Dist.CLIENT)
    public static boolean isCreator() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return inCompany && mc.player != null && mc.player.getUUID().equals(creatorId);
    }

    public static long getBalance() {
        return balance;
    }

    public static int getReputation() {
        return reputation;
    }

    public static int getCompanyLevel() {
        return companyLevel;
    }

    public static int getLinkSlots() {
        return linkSlots;
    }

    public static long getNextUpgradeCost() {
        return nextUpgradeCost;
    }

    public static List<MemberEntry> getMembers() {
        return members;
    }

    public static List<JoinableEntry> getJoinables() {
        return joinables;
    }
}
