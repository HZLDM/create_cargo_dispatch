package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.company.CompanyLevelRules;
import com.hzldm.createcargodispatch.company.ReputationRules;
import com.hzldm.createcargodispatch.menu.CompanyMenu;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * 联合运输页「公司成长」双卡片面板。
 *
 * <p>两张卡片语义完全不同，颜色/边框/标题各自独立，避免“等级与声望分不清”：</p>
 * <ul>
 *   <li>左卡 · 金色「公司资质」：公司等级（花货运币晋升）+ 货运币余额 + 升级按钮</li>
 *   <li>右卡 · 绿色「公司声望」：声望等级（完成订单积累）+ 级内进度条 + 可链接站点数</li>
 * </ul>
 *
 * <p>数据全部来自 {@link SyncCompanyPayload} 服务端权威值；客户端只用 {@link ReputationRules}
 * 的常量（不读配置）公式派生声望等级与级内进度，双端必然一致。</p>
 */
public final class CompanyProgressionPanel {

    // ---------------- 面板区（topPos 相对） ----------------
    public static final int CARD_TOP = 21;
    public static final int CARD_H = 50;
    /** 卡片区最下沿（CompanyScreen 邀请区从 75 开始） */
    public static final int PANEL_BOTTOM = 71;

    // ---------------- 左卡：公司资质（金） ----------------
    private static final int LX = 8;
    private static final int LW = 96;
    // ---------------- 右卡：公司声望（绿） ----------------
    private static final int RX = 112;
    private static final int RW = 88;

    // 左卡升级按钮（leftPos 相对）
    private static final int BTN_X = LX + 7;   // 15
    private static final int BTN_Y = CARD_TOP + 37; // 58
    private static final int BTN_W = LW - 14;   // 82
    private static final int BTN_H = 11;

    private static final int GOLD = 0xFFFFD760;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFFA0A0A0;
    private static final int GREEN = 0xFF7CFC7C;
    private static final int RED = 0xFFFF7070;
    private static final int CARD_BG = 0xFF1A1A1A;
    private static final int CARD_BG2 = 0xFF202020;
    private static final int BAR_BG = 0xFF0F0F0F;
    private static final int BAR_FILL = 0xFF4FC070;
    private static final int BORDER_GOLD = 0xFFB89438;
    private static final int BORDER_GREEN = 0xFF4E9A5A;

    private CompanyProgressionPanel() {
    }

    /** 绘制双卡片（topPos+CARD_TOP .. topPos+PANEL_BOTTOM） */
    public static void render(GuiGraphics g, Font font, int leftPos, int topPos,
                              CompanyMenu menu, boolean creator,
                              int mouseX, int mouseY) {
        renderTierCard(g, font, leftPos, topPos, menu, creator, mouseX, mouseY);
        renderReputationCard(g, font, leftPos, topPos, menu, mouseX, mouseY);
    }

    // ---------------- 左卡：公司资质 ----------------

    private static void renderTierCard(GuiGraphics g, Font font, int leftPos, int topPos,
                                       CompanyMenu menu, boolean creator,
                                       int mouseX, int mouseY) {
        int x = leftPos + LX;
        int y = topPos + CARD_TOP;
        g.fill(x, y, x + LW, y + CARD_H, CARD_BG);
        g.renderOutline(x, y, LW, CARD_H, BORDER_GOLD);

        // 卡片标题（金色，卡片内顶部高亮条）
        g.fill(x + 1, y + 1, x + LW - 1, y + 11, 0xFF2A2412);
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.card_tier"),
                x + 5, y + 3, GOLD, false);

        // 公司等级值
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.level_value",
                menu.getCompanyLevel(), CompanyLevelRules.MAX_COMPANY_LEVEL),
                x + 6, y + 15, TEXT, false);

        // 货运币余额
        String coin = Component.translatable("create_cargo_dispatch.currency.coin").getString();
        g.drawString(font, coin + ": " + formatLong(menu.getBalance()),
                x + 6, y + 26, GOLD, false);

        // 升级按钮（仅创建者；其他成员显示不可操作的灰色说明）
        long nextCost = menu.getNextUpgradeCost();
        if (creator) {
            int repLevel = ReputationRules.levelOf(menu.getReputation());
            int requiredRep = CompanyLevelRules.requiredRepLevel(menu.getCompanyLevel() + 1);
            drawUpgradeButton(g, font, x + 7, y + 37, BTN_W, menu.getBalance(), nextCost,
                    repLevel >= requiredRep, requiredRep, mouseX, mouseY);
        } else {
            Component hint = nextCost == SyncCompanyPayload.COST_MAX_LEVEL
                    ? Component.translatable("create_cargo_dispatch.company.level_max_short")
                    : Component.translatable("create_cargo_dispatch.company.upgrade_locked");
            // 提示文案短（“仅创建者可升级”7 字 ≈63px < 卡内宽 84），直接绘制
            g.drawString(font, hint, x + 6, y + 39, SUB, false);
        }
    }

    /**
     * 升级按钮：满级显示已满级；声望不足红色显示门槛（悬停看详情）；
     * 否则显示费用，余额足够金色、不足红色（点击由服务端最终裁决）。
     */
    private static void drawUpgradeButton(GuiGraphics g, Font font,
                                          int x, int y, int w, long balance, long nextCost,
                                          boolean repOk, int requiredRep,
                                          int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H;
        if (nextCost == SyncCompanyPayload.COST_MAX_LEVEL) {
            g.fill(x, y, x + w, y + BTN_H, 0xFF3A3A3A);
            g.renderOutline(x, y, w, BTN_H, 0xFF666666);
            String label = Component.translatable("create_cargo_dispatch.company.level_max_short").getString();
            g.drawString(font, label, x + (w - font.width(label)) / 2, y + 2, SUB, false);
            return;
        }
        // 声望门槛未达标：按钮红色警示并拦截视觉焦点（服务端同步权威拒绝）
        if (!repOk) {
            int base = 0xFF5A2A2A;
            g.fill(x, y, x + w, y + BTN_H, hover ? shift(base) : base);
            g.renderOutline(x, y, w, BTN_H, 0xFF8A4A4A);
            String label = Component.translatable(
                    "create_cargo_dispatch.company.upgrade_rep_short", requiredRep).getString();
            label = font.plainSubstrByWidth(label, w - 6);
            g.drawString(font, label, x + (w - font.width(label)) / 2, y + 2, RED, false);
            if (hover) {
                g.renderComponentTooltip(font, List.of(Component.translatable(
                        "create_cargo_dispatch.company.level_rep_required", requiredRep)), mouseX, mouseY);
            }
            return;
        }
        boolean afford = balance >= nextCost;
        int base = afford ? 0xFF6A5410 : 0xFF5A2A2A;
        int color = hover ? shift(base) : base;
        g.fill(x, y, x + w, y + BTN_H, color);
        g.renderOutline(x, y, w, BTN_H, afford ? BORDER_GOLD : 0xFF8A4A4A);
        // “升级 2,000”；极端长数字截断
        String label = Component.translatable("create_cargo_dispatch.company.upgrade_cost", formatLong(nextCost)).getString();
        label = font.plainSubstrByWidth(label, w - 6);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 2, afford ? GOLD : RED, false);
    }

    // ---------------- 右卡：公司声望 ----------------

    private static void renderReputationCard(GuiGraphics g, Font font, int leftPos, int topPos,
                                             CompanyMenu menu, int mouseX, int mouseY) {
        int reputation = menu.getReputation();
        int repLevel = ReputationRules.levelOf(reputation);
        int curBase = ReputationRules.currentLevelBaseRep(reputation);
        int nextBase = ReputationRules.nextLevelBaseRep(reputation);
        boolean maxRep = ReputationRules.isMaxLevel(reputation);

        int x = leftPos + RX;
        int y = topPos + CARD_TOP;
        g.fill(x, y, x + RW, y + CARD_H, CARD_BG2);
        g.renderOutline(x, y, RW, CARD_H, BORDER_GREEN);

        // 卡片标题（绿色，顶部高亮条）
        g.fill(x + 1, y + 1, x + RW - 1, y + 11, 0xFF122A18);
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.card_reputation"),
                x + 5, y + 3, GREEN, false);

        // 声望等级值
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.rep_value", repLevel),
                x + 6, y + 15, TEXT, false);

        // 级内进度条（百分比叠在条右侧，黑底绿字任何进度下都清晰）
        int barX = x + 5;
        int barY = y + 26;
        int barW = RW - 10;
        float ratio;
        if (maxRep) {
            ratio = 1.0F;
        } else {
            int span = Math.max(1, nextBase - curBase);
            ratio = Math.max(0F, Math.min(1F, (reputation - curBase) / (float) span));
        }
        g.fill(barX, barY, barX + barW, barY + 8, BAR_BG);
        int fillW = Math.round(barW * ratio);
        if (fillW > 0) g.fill(barX, barY, barX + fillW, barY + 8, BAR_FILL);
        g.renderOutline(barX, barY, barW, 8, BORDER_GREEN);
        String percentText = maxRep
                ? Component.translatable("create_cargo_dispatch.company.rep_max").getString()
                : (int) (ratio * 100) + "%";
        g.drawString(font, percentText, barX + barW - 4 - font.width(percentText), barY + 1, GREEN, false);

        // 可链接站点数
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.link_slots", menu.getLinkSlots()),
                x + 6, y + 38, SUB, false);

        // 悬停声望卡：当前/下一级所需绝对声望（精确数值，补百分比/进度条之外的信息）
        boolean hoverCard = mouseX >= x && mouseX < x + RW && mouseY >= y && mouseY < y + CARD_H;
        if (hoverCard) {
            Component tip = maxRep
                    ? Component.translatable("create_cargo_dispatch.company.rep_tooltip_max", reputation, repLevel)
                    : Component.translatable("create_cargo_dispatch.company.rep_tooltip",
                            reputation, nextBase, repLevel, ReputationRules.MAX_REPUTATION_LEVEL);
            g.renderComponentTooltip(font, List.of(tip), mouseX, mouseY);
        }
    }

    /** 升级按钮点击命中：仅创建者且未满级；返回 true 表示已消费事件 */
    public static boolean mouseClicked(CompanyMenu menu, double mouseX, double mouseY,
                                       int leftPos, int topPos, boolean creator) {
        if (!creator || menu.getNextUpgradeCost() == SyncCompanyPayload.COST_MAX_LEVEL) return false;
        int x = leftPos + BTN_X;
        int y = topPos + BTN_Y;
        if (mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H) {
            menu.upgradeLevel();
            return true;
        }
        return false;
    }

    /** 悬停高亮（简单加亮） */
    private static int shift(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) + 28);
        int b = Math.min(255, (argb & 0xFF) + 28);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static String formatLong(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
