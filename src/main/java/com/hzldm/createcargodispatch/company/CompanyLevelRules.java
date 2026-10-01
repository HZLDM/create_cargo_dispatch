package com.hzldm.createcargodispatch.company;

import com.hzldm.createcargodispatch.config.ModConfig;

/**
 * 公司等级规则（消耗货运币晋升，与声望成长相互独立）
 *
 * <p>公司等级代表公司的「资质/牌照」，用于解锁高级资产购买权限。
 * 升级费用二次递增：{@code costBase * L * L}（L=当前等级）。</p>
 */
public final class CompanyLevelRules {

    /** 公司等级上限（区间 1..MAX） */
    public static final int MAX_COMPANY_LEVEL = 5;

    private CompanyLevelRules() {
    }

    public static boolean isMaxLevel(int level) {
        return level >= MAX_COMPANY_LEVEL;
    }

    /**
     * 晋升到 targetLevel 所需的最低声望等级。
     * 原理：公司资质必须靠真实配送积累的声望背书，公司 Lv.N ⇒ 声望 Lv.N（1 级为初始态不设卡）。
     */
    public static int requiredRepLevel(int targetLevel) {
        return Math.max(1, targetLevel);
    }

    /**
     * 从 currentLevel 升到下一级所需货运币
     * （满级时返回 Long.MAX_VALUE 表示不可再升）
     */
    public static long upgradeCost(int currentLevel) {
        if (currentLevel < 1) currentLevel = 1;
        if (currentLevel >= MAX_COMPANY_LEVEL) return Long.MAX_VALUE;
        return (long) ModConfig.getCompanyUpgradeCostBase() * currentLevel * currentLevel;
    }
}
