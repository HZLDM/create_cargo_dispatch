package com.hzldm.createcargodispatch.company;

import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload.JoinableEntry;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload.MemberEntry;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 联合运输公司业务编排层
 *
 * 原理（SRP 分层）：
 *  - {@link CompanyStore} 只负责公司状态与持久化；本类负责「状态变更 ↔ 订单池切换 ↔ 客户端同步 ↔ 聊天通知」编排
 *  - 任何变更后对受影响在线玩家统一 {@link #refreshPlayerViews}：公司状态 + 活跃订单 + PENDING 订单三件套整表重推，
 *    客户端整表替换，天然收敛到最终一致（实时同步，不依赖轮询）
 *  - 个人↔公司订单切换全部委托 {@link OrderManager}，本类不直接碰订单池
 */
public final class CompanyService {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Company");

    private CompanyService() {
    }

    // =====================================================
    // 同步包构建 / 推送
    // =====================================================

    /** 按玩家当前归属构建同步包：在司→成员视图；不在司→可加入列表视图 */
    public static SyncCompanyPayload buildSync(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        UUID playerId = player.getUUID();
        Company company = store.getCompanyOfPlayer(playerId);
        if (company != null) {
            // 服务端权威派生：可链接槽位、下一级升级费用（满级=-1）；客户端只展示不计算
            long nextCost = CompanyLevelRules.isMaxLevel(company.getCompanyLevel())
                    ? SyncCompanyPayload.COST_MAX_LEVEL
                    : CompanyLevelRules.upgradeCost(company.getCompanyLevel());
            return new SyncCompanyPayload(true, company.getId(), company.getName(), company.getCreator(),
                    buildMembers(company, player.server), List.of(),
                    company.getBalance(), company.getReputation(), company.getCompanyLevel(),
                    company.getLinkSlots(), nextCost);
        }
        List<JoinableEntry> joinables = new ArrayList<>();
        for (Company c : store.listCompanies()) {
            joinables.add(new JoinableEntry(c.getId(), c.getName(),
                    resolveDisplayName(player.server, c.getCreator()), c.size()));
        }
        return new SyncCompanyPayload(false, null, "", null, List.of(), joinables,
                0L, 0, 1, 0, SyncCompanyPayload.COST_MAX_LEVEL);
    }

    /** 立即向玩家推送最新公司状态 */
    public static void sendSync(ServerPlayer player) {
        if (player == null) return;
        try {
            PacketDistributor.sendToPlayer(player, buildSync(player));
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 推送联合运输状态失败 player={}", player.getUUID(), t);
        }
    }

    /**
     * 货运操作前置门槛：玩家必须隶属于某个公司。
     * 原理：连接站点/接单/提交等一切货运操作只服务于公司业务，无公司时统一拦截并给出明确提示，
     *       服务端权威校验，防止手工构造数据包绕过客户端禁用态。
     * @return true=已加入公司可继续；false=已发送拒绝提示，调用方应直接 return
     */
    public static boolean requireCompanyMembership(ServerPlayer player) {
        return requireCompanyMembership(player, true);
    }

    /**
     * @param notify true=主动操作被拒时发聊天提示；false=查看类轮询静默拒绝（提示由页面内黄字承担，避免刷屏）
     */
    public static boolean requireCompanyMembership(ServerPlayer player, boolean notify) {
        if (player == null) return false;
        UUID companyId = CompanyStore.get(player.serverLevel()).getCompanyIdOfPlayer(player.getUUID());
        if (companyId != null) return true;
        if (notify) {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.required")
                    .withStyle(ChatFormatting.RED));
        }
        return false;
    }

    private static List<MemberEntry> buildMembers(Company company, MinecraftServer server) {
        List<MemberEntry> list = new ArrayList<>(company.size());
        for (UUID memberId : company.members()) {
            list.add(new MemberEntry(memberId, resolveDisplayName(server, memberId),
                    company.isCreator(memberId)));
        }
        return list;
    }

    /** 在线优先取实时名；离线回退 GameProfileCache 档案名并标注（离线） */
    private static String resolveDisplayName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getName().getString();
        String fallback = null;
        try {
            // 1.21.1：MinecraftServer#getProfileCache() 返回 net.minecraft.server.players.GameProfileCache
            if (server.getProfileCache() != null) {
                Optional<GameProfile> profile = server.getProfileCache().get(id);
                if (profile.isPresent()) fallback = profile.get().getName();
            }
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] 解析离线玩家名失败 {}", id, t);
        }
        return (fallback != null ? fallback : id.toString().substring(0, 8))
                + Component.translatable("create_cargo_dispatch.company.offline_suffix").getString();
    }

    // =====================================================
    // 业务操作
    // =====================================================

    /** 创建公司：校验 → 建司 → 个人订单转公司订单 → 同步 */
    public static void createCompany(ServerPlayer player, String rawName) {
        ServerLevel level = player.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        if (store.getCompanyIdOfPlayer(player.getUUID()) != null) {
            fail(player, Component.translatable("create_cargo_dispatch.company.already_in_company"));
            return;
        }
        String errorKey = store.validateName(rawName);
        if (errorKey != null) {
            fail(player, Component.translatable(errorKey));
            return;
        }
        Company company = store.create(player.getUUID(), rawName.trim(), level.getGameTime());
        if (company == null) {
            // 并发兜底：预校验后仍可能有并发请求抢先建立归属
            fail(player, Component.translatable("create_cargo_dispatch.company.already_in_company"));
            return;
        }
        // 建立联络线绑定：无公司期间不能连接站点，故新公司初始连接为空
        try {
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level)
                    .bindPlayer(level, player.getUUID(), company.getId());
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 创建公司后绑定联络线失败 player={}", player.getUUID(), t);
        }
        try {
            OrderManager.convertPersonalOrdersToCompany(level, player.getUUID(), company.getId());
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 个人订单转公司订单失败 player={}", player.getUUID(), t);
        }
        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.created", company.getName())
                .withStyle(ChatFormatting.GREEN));
        refreshPlayerViews(level, company.members());
        OrderManager.notifyPendingChanged(level);
    }

    /** 加入公司：校验 → 入司 → 个人订单并入公司池 → 全员同步 + 聊天通知 */
    public static void joinCompany(ServerPlayer player, UUID companyId) {
        ServerLevel level = player.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        UUID existing = store.getCompanyIdOfPlayer(player.getUUID());
        if (existing != null) {
            if (existing.equals(companyId)) {
                sendSync(player); // 幂等：已在该公司，重推状态即可
            } else {
                fail(player, Component.translatable("create_cargo_dispatch.company.already_in_company"));
            }
            return;
        }
        Company company = store.getCompany(companyId);
        if (company == null) {
            fail(player, Component.translatable("create_cargo_dispatch.company.not_found"));
            return;
        }
        if (!store.join(companyId, player.getUUID())) {
            fail(player, Component.translatable("create_cargo_dispatch.company.not_found"));
            return;
        }
        // 绑定后立即拿到公司共享连接列表（推给新成员）
        try {
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level)
                    .bindPlayer(level, player.getUUID(), companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 入司后绑定联络线失败 player={}", player.getUUID(), t);
        }
        try {
            OrderManager.convertPersonalOrdersToCompany(level, player.getUUID(), companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 入司订单转换失败 player={}", player.getUUID(), t);
        }
        String playerName = player.getName().getString();
        notifyMembers(level, company, Component.translatable(
                "create_cargo_dispatch.company.member_joined", playerName, company.getName()));
        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.joined", company.getName())
                .withStyle(ChatFormatting.GREEN));
        refreshPlayerViews(level, company.members());
        OrderManager.notifyPendingChanged(level);
    }

    /** 退出：创建者=解散（公司订单全删）；普通成员=其配送中订单放回公司池后离司 */
    public static void leaveCompany(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        UUID playerId = player.getUUID();
        Company company = store.getCompanyOfPlayer(playerId);
        if (company == null) {
            fail(player, Component.translatable("create_cargo_dispatch.company.not_in_company"));
            return;
        }
        if (company.isCreator(playerId)) {
            disband(level, store, company);
            return;
        }
        UUID companyId = company.getId();
        String companyName = company.getName();
        try {
            OrderManager.releasePlayerCompanyOrders(level, playerId, companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 离司订单释放失败 player={}", playerId, t);
        }
        Company after = store.leave(playerId);
        if (after == null) {
            fail(player, Component.translatable("create_cargo_dispatch.company.no_permission"));
            return;
        }
        // 连接是公司资产留在公司；仅解绑个人索引并推空连接视图
        try {
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level).unbindPlayer(level, playerId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 离司解绑联络线失败 player={}", playerId, t);
        }
        List<UUID> affected = new ArrayList<>(after.members());
        affected.add(playerId);
        notifyMembers(level, after, Component.translatable(
                "create_cargo_dispatch.company.member_left", player.getName().getString(), companyName));
        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.left", companyName)
                .withStyle(ChatFormatting.YELLOW));
        refreshPlayerViews(level, affected);
        OrderManager.notifyPendingChanged(level);
    }

    /** 创建者解散：订单全删（用户决策不做归属转移）→ 删公司 → 全员通知 + 同步 */
    private static void disband(ServerLevel level, CompanyStore store, Company company) {
        UUID companyId = company.getId();
        String companyName = company.getName();
        List<UUID> members = company.members();
        try {
            OrderManager.deleteCompanyOrders(level, companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 解散删除公司订单失败 company={}", companyId, t);
        }
        List<UUID> disbanded = store.disband(company.getCreator());
        if (disbanded.isEmpty()) {
            ServerPlayer creator = level.getServer().getPlayerList().getPlayer(company.getCreator());
            if (creator != null) {
                fail(creator, Component.translatable("create_cargo_dispatch.company.no_permission"));
            }
            return;
        }
        // 级联删除公司全部站点连接（内部解绑成员索引并推空连接列表）
        try {
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level).removeCompany(level, companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 解散级联删除联络线失败 company={}", companyId, t);
        }
        notifyOnlinePlayers(level, members, Component.translatable(
                "create_cargo_dispatch.company.disbanded", companyName).withStyle(ChatFormatting.RED));
        refreshPlayerViews(level, members);
        OrderManager.notifyPendingChanged(level);
    }

    /** 创建者踢人（目标可离线）：被踢者配送中订单放回公司池 */
    public static void kickMember(ServerPlayer operator, UUID targetId) {
        ServerLevel level = operator.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        Company company = store.getCompanyOfPlayer(operator.getUUID());
        if (company == null || !company.isCreator(operator.getUUID())) {
            fail(operator, Component.translatable("create_cargo_dispatch.company.no_permission"));
            return;
        }
        String targetName = resolveDisplayName(level.getServer(), targetId);
        Company after = store.kick(operator.getUUID(), targetId);
        if (after == null) {
            fail(operator, Component.translatable("create_cargo_dispatch.company.no_permission"));
            return;
        }
        try {
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level).unbindPlayer(level, targetId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 踢人解绑联络线失败 target={}", targetId, t);
        }
        try {
            OrderManager.releasePlayerCompanyOrders(level, targetId, after.getId());
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 踢人订单释放失败 target={}", targetId, t);
        }
        List<UUID> affected = new ArrayList<>(after.members());
        affected.add(targetId);
        notifyMembers(level, after, Component.translatable(
                "create_cargo_dispatch.company.member_kicked", targetName, after.getName()));
        ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
        if (target != null) {
            target.sendSystemMessage(Component.translatable(
                    "create_cargo_dispatch.company.kicked", after.getName()).withStyle(ChatFormatting.RED));
        }
        refreshPlayerViews(level, affected);
        OrderManager.notifyPendingChanged(level);
    }

    /**
     * 创建者按在线玩家名邀请：目标在线且无公司时收到可点击聊天消息（一键 RUN_COMMAND 入司）。
     * 公司列表本身开放加入，邀请是「通知 + 快捷入口」，不做独立邀请态存储（无内存滞留）。
     */
    public static void inviteByName(ServerPlayer operator, String targetNameRaw) {
        if (targetNameRaw == null || targetNameRaw.isBlank()) return;
        ServerLevel level = operator.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        Company company = store.getCompanyOfPlayer(operator.getUUID());
        if (company == null || !company.isCreator(operator.getUUID())) {
            fail(operator, Component.translatable("create_cargo_dispatch.company.no_permission"));
            return;
        }
        String targetName = targetNameRaw.trim();
        ServerPlayer target = level.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            fail(operator, Component.translatable("create_cargo_dispatch.company.player_not_found"));
            return;
        }
        if (store.getCompanyIdOfPlayer(target.getUUID()) != null) {
            fail(operator, Component.translatable("create_cargo_dispatch.company.player_busy"));
            return;
        }
        // 点击消息执行注册的公开子指令入司（权限 0）
        Component accept = Component.translatable("create_cargo_dispatch.company.invite_accept")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/createcargodispatch joincompany " + company.getId())));
        target.sendSystemMessage(Component.translatable(
                "create_cargo_dispatch.company.invite_received",
                operator.getName().getString(), company.getName()).append(" ").append(accept));
        operator.sendSystemMessage(Component.translatable(
                "create_cargo_dispatch.company.invited", targetName).withStyle(ChatFormatting.GREEN));
    }

    // =====================================================
    // 经济：订单结算（货运币 + 声望） / 公司等级晋升
    // =====================================================

    /**
     * 订单完成统一结算入口：货运币入公司账户 + 按收益折算声望。
     * 原子事务（{@link CompanyStore#settleOrder}），完成后向全体在线成员实时同步，
     * 声望跨级时全公司广播并提示新的可链接站点数。
     *
     * @return 结算结果；公司不存在或无收益返回 null
     */
    public static CompanyStore.SettlementResult settleCompletedOrder(ServerLevel level,
                                                                     UUID companyId, int coin) {
        if (companyId == null || coin <= 0) return null;
        CompanyStore store = CompanyStore.get(level);
        Company company = store.getCompany(companyId);
        if (company == null) return null;
        int repGain = ReputationRules.reputationGain(coin);
        CompanyStore.SettlementResult result = store.settleOrder(companyId, coin, repGain);
        if (result == null) return null;
        // 实时同步余额/声望给全体在线成员
        for (UUID memberId : company.members()) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(memberId);
            if (online != null) sendSync(online);
        }
        // 声望跨级广播（附带新的可链接站点上限）
        if (result.newRepLevel() > result.oldRepLevel()) {
            int slots = ReputationRules.linkSlotsForLevel(result.newRepLevel());
            notifyMembers(level, company, Component.translatable(
                    "create_cargo_dispatch.company.rep_level_up", result.newRepLevel(), slots)
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        }
        return result;
    }

    /** 创建者消耗公司货运币提升公司等级（费用/满级均由规则类计算，服务端权威） */
    public static void upgradeCompanyLevel(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompanyStore store = CompanyStore.get(level);
        Company company = store.getCompanyOfPlayer(player.getUUID());
        if (company == null) {
            fail(player, Component.translatable("create_cargo_dispatch.company.required"));
            return;
        }
        if (!company.isCreator(player.getUUID())) {
            fail(player, Component.translatable("create_cargo_dispatch.company.no_permission"));
            return;
        }
        int current = company.getCompanyLevel();
        if (CompanyLevelRules.isMaxLevel(current)) {
            fail(player, Component.translatable("create_cargo_dispatch.company.level_max"));
            return;
        }
        // 声望门槛（先于扣费校验）：晋升目标等级需声望等级同步达标
        int target = current + 1;
        int requiredRep = CompanyLevelRules.requiredRepLevel(target);
        if (company.getReputationLevel() < requiredRep) {
            fail(player, Component.translatable(
                    "create_cargo_dispatch.company.level_rep_required", requiredRep));
            return;
        }
        long cost = CompanyLevelRules.upgradeCost(current);
        Company.LevelUpResult result = store.tryUpgradeCompany(company.getId(), cost);
        switch (result) {
            case MAX_LEVEL -> fail(player, Component.translatable("create_cargo_dispatch.company.level_max"));
            case INSUFFICIENT_FUNDS -> fail(player, Component.translatable(
                    "create_cargo_dispatch.company.level_insufficient", cost));
            case OK -> {
                notifyMembers(level, company, Component.translatable(
                        "create_cargo_dispatch.company.level_up", target, cost)
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                for (UUID memberId : company.members()) {
                    ServerPlayer online = level.getServer().getPlayerList().getPlayer(memberId);
                    if (online != null) sendSync(online);
                }
            }
        }
    }

    // =====================================================
    // 通知 / 视图刷新
    // =====================================================

    /** 给公司内全体在线成员发聊天通知 */
    private static void notifyMembers(ServerLevel level, Company company, Component message) {
        notifyOnlinePlayers(level, company.members(), message);
    }

    private static void notifyOnlinePlayers(ServerLevel level, List<UUID> playerIds, Component message) {
        MinecraftServer server = level.getServer();
        for (UUID id : playerIds) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(message);
        }
    }

    /**
     * 受影响玩家视图三件套整表重推：
     * 公司状态（SyncCompanyPayload）+ 活跃订单（含同公司成员配送单）+ PENDING 过滤视图（0 RTT）
     */
    private static void refreshPlayerViews(ServerLevel level, List<UUID> playerIds) {
        MinecraftServer server = level.getServer();
        for (UUID id : playerIds) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue; // 离线成员登录时由 PlayerLoggedInEvent 初始推送补齐
            try {
                sendSync(p);
                List<SyncActiveOrdersPayload.ActiveOrderEntry> active =
                        OrderManager.getAcceptedOrdersByPlayer(level, id).stream()
                                .map(SyncActiveOrdersPayload.ActiveOrderEntry::from)
                                .toList();
                PacketDistributor.sendToPlayer(p, new SyncActiveOrdersPayload(active));
                OrderManager.pushFilteredPendingOrdersToPlayer(level, id);
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] 刷新玩家联合运输视图失败 player={}", id, t);
            }
        }
    }

    private static void fail(ServerPlayer player, Component message) {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
    }
}
