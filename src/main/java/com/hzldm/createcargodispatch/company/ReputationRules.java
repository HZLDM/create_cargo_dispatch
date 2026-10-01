package com.hzldm.createcargodispatch.company;

import com.hzldm.createcargodispatch.config.ModConfig;

/**
 * 公司声望等级规则（纯函数、无状态，OCP：数值走配置，规则集中于此便于附属扩展）
 *
 * <p>声望值只增不减，等级是从总声望派生的属性（不单独存储，避免双写不一致）。
 * 升到第 L 级所需的累计声望：{@code LEVEL_BASE * (L-1) * L}，二次曲线放大后期投入。</p>
 *
 * <p>声望等级决定公司「可链接站点数量」上限，等级越高路网越大。</p>
 */
public final class ReputationRules {

    /** 声望满级（等级区间 1..MAX） */
    public static final int MAX_REPUTATION_LEVEL = 10;

    /** 升级曲线基数：required(L) = BASE * (L-1) * L */
    private static final int LEVEL_BASE = 250;

    private ReputationRules() {
    }

    /** 升到指定等级所需的累计声望（1 级为 0；超出满级按满级门槛） */
    public static int requiredRepForLevel(int level) {
        if (level <= 1) return 0;
        int clamped = Math.min(level, MAX_REPUTATION_LEVEL);
        return LEVEL_BASE * (clamped - 1) * clamped;
    }

    /** 总声望对应的声望等级（1..MAX_REPUTATION_LEVEL） */
    public static int levelOf(int reputation) {
        if (reputation <= 0) return 1;
        int level = 1;
        while (level < MAX_REPUTATION_LEVEL && reputation >= requiredRepForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    /** 当前等级起点所需累计声望（进度条下端） */
    public static int currentLevelBaseRep(int reputation) {
        return requiredRepForLevel(levelOf(reputation));
    }

    /** 下一等级起点所需累计声望（进度条上端）；满级时返回当前门槛 */
    public static int nextLevelBaseRep(int reputation) {
        int level = levelOf(reputation);
        if (level >= MAX_REPUTATION_LEVEL) return requiredRepForLevel(MAX_REPUTATION_LEVEL);
        return requiredRepForLevel(level + 1);
    }

    public static boolean isMaxLevel(int reputation) {
        return levelOf(reputation) >= MAX_REPUTATION_LEVEL;
    }

    /** 可链接站点数：基础槽位 + 每升 1 个声望等级增加的槽位 */
    public static int linkSlotsForLevel(int reputationLevel) {
        int level = Math.max(1, Math.min(reputationLevel, MAX_REPUTATION_LEVEL));
        return ModConfig.getLinkSlotsBase() + (level - 1) * ModConfig.getLinkSlotsPerLevel();
    }

    /** 按当前总声望查询可链接站点数 */
    public static int linkSlots(int reputation) {
        return linkSlotsForLevel(levelOf(reputation));
    }

    /**
     * 完成订单按货运币奖励结算声望：与收益正相关，设保底值避免短途单无成长。
     * 公式：max(每单保底, round(货运币 * 声望换算比))
     */
    public static int reputationGain(int coinReward) {
        if (coinReward <= 0) return 0;
        int byCoin = (int) Math.round(coinReward * ModConfig.getRepPerCoin());
        return Math.max(ModConfig.getRepMinPerOrder(), byCoin);
    }
}
