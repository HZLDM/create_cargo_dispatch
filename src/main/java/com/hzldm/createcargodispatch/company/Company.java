package com.hzldm.createcargodispatch.company;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 联合运输公司数据模型
 *
 * 原理：
 *  - 创建者固定为首个成员（members[0]），创建者退出即解散
 *  - 成员列表用 CopyOnWriteArrayList：读远多于写（UI/订单广播每 tick 可能读），遍历天然安全
 *  - 持久化由 CompanyStore 负责，本类只承载内存状态
 */
public final class Company {

    private final UUID id;
    private volatile String name;
    private final UUID creator;
    private final long createdAt;
    private final CopyOnWriteArrayList<UUID> members = new CopyOnWriteArrayList<>();

    /** 公司账户货运币余额（long 防大额溢出，AtomicLong 保证结算原子性） */
    private final AtomicLong balance = new AtomicLong(0);
    /** 累计声望值（只增；声望等级由 ReputationRules 派生） */
    private final AtomicInteger reputation = new AtomicInteger(0);
    /** 公司资质等级（消耗货运币晋升，独立于声望等级） */
    private final AtomicInteger companyLevel = new AtomicInteger(1);

    /** 公司升级扣费结果（供服务层精确提示） */
    public enum LevelUpResult { OK, MAX_LEVEL, INSUFFICIENT_FUNDS }

    public Company(UUID id, String name, UUID creator, long createdAt) {
        this.id = id;
        this.name = name;
        this.creator = creator;
        this.createdAt = createdAt;
        this.members.add(creator);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** 仅 CompanyStore 在校验重名后调用 */
    void setName(String name) {
        this.name = name;
    }

    public UUID getCreator() {
        return creator;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isCreator(UUID playerId) {
        return creator.equals(playerId);
    }

    public boolean contains(UUID playerId) {
        return playerId != null && members.contains(playerId);
    }

    /** 添加成员，返回是否真正新增（幂等） */
    boolean addMember(UUID playerId) {
        if (playerId == null || members.contains(playerId)) return false;
        members.add(playerId);
        return true;
    }

    /** 移除成员，返回是否真正移除 */
    boolean removeMember(UUID playerId) {
        return members.remove(playerId);
    }

    public int size() {
        return members.size();
    }

    /** 有序只读快照（创建者在首位） */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    // =====================================================
    // 经济系统：货运币余额 / 声望 / 公司等级
    // =====================================================

    public long getBalance() {
        return balance.get();
    }

    /** 增减货运币（正数入账/负数支出），返回变更后余额；夹取到 [0, Long.MAX_VALUE] */
    long addBalance(long delta) {
        return balance.accumulateAndGet(delta, (cur, d) -> {
            long next = cur + d;
            if (d > 0 && next < cur) return Long.MAX_VALUE; // 正溢出回绕则夹顶
            return Math.max(0L, next);
        });
    }

    public int getReputation() {
        return reputation.get();
    }

    /** 增加声望（只传正数），返回变更后的总声望；等级由调用方用 ReputationRules 比较 */
    int addReputation(int delta) {
        if (delta <= 0) return reputation.get();
        return reputation.accumulateAndGet(delta, (cur, d) -> cur >= Integer.MAX_VALUE - d
                ? Integer.MAX_VALUE : cur + d);
    }

    /** 当前声望等级（委托规则类派生） */
    public int getReputationLevel() {
        return ReputationRules.levelOf(reputation.get());
    }

    /** 当前可链接站点数（声望等级联动） */
    public int getLinkSlots() {
        return ReputationRules.linkSlots(reputation.get());
    }

    public int getCompanyLevel() {
        return companyLevel.get();
    }

    /**
     * 原子「扣费 + 升级」：复合操作加 synchronized，避免「查到余额够、扣费瞬间被并发花光」。
     * 调用方先用 {@link CompanyLevelRules#upgradeCost(int)} 算出 cost 传入。
     */
    synchronized LevelUpResult trySpendAndLevelUp(long cost) {
        int level = companyLevel.get();
        if (CompanyLevelRules.isMaxLevel(level)) return LevelUpResult.MAX_LEVEL;
        if (balance.get() < cost) return LevelUpResult.INSUFFICIENT_FUNDS;
        balance.addAndGet(-cost);
        companyLevel.set(level + 1);
        return LevelUpResult.OK;
    }

    /** 仅持久化加载时使用：直接还原经济三要素（不经校验） */
    void loadEconomy(long savedBalance, int savedReputation, int savedLevel) {
        balance.set(Math.max(0L, savedBalance));
        reputation.set(Math.max(0, savedReputation));
        companyLevel.set(Math.max(1, savedLevel));
    }
}
