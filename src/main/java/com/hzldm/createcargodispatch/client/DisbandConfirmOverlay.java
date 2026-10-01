package com.hzldm.createcargodispatch.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 解散公司二次确认覆盖层（叠加在联合运输页之上，非独立 Screen）。
 *
 * 原理：几何常量绘制/命中两处共用；调用方负责开关状态与解散动作，
 * 本类只回答「画成什么样 / 这一下点中了什么」，与页面业务解耦（SRP）。
 */
public final class DisbandConfirmOverlay {

    private static final int DLG_W = 172;
    private static final int DLG_H = 96;
    private static final int DLG_PY_REL = 54;
    private static final int DLG_DIM = 0xE0000000; // 88% 黑，背景文字/列表彻底弱化
    private static final int DLG_BTN_W = 68;
    private static final int DLG_BTN_H = 14;
    private static final int DLG_BTN_PAD = 14;

    /** 点击结果：CONFIRM=确认解散；DISMISS=点取消关闭；NONE=遮罩吞掉但弹窗保留 */
    public enum ClickResult { CONFIRM, DISMISS, NONE }

    private DisbandConfirmOverlay() {
    }

    /** 绘制全屏压暗遮罩 + 对话框（screenWidth/Height 用于遮罩覆盖整个视口） */
    public static void render(GuiGraphics g, Font font, int leftPos, int topPos, int imageWidth,
                              int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int px = leftPos + (imageWidth - DLG_W) / 2;
        int py = topPos + DLG_PY_REL;
        g.fill(0, 0, screenWidth, screenHeight, DLG_DIM);
        g.fill(px, py, px + DLG_W, py + DLG_H, 0xFF1F1F1F);
        g.renderOutline(px, py, DLG_W, DLG_H, 0xFFFF6060);

        g.drawCenteredString(font,
                Component.translatable("create_cargo_dispatch.company.disband_confirm_title"),
                px + DLG_W / 2, py + 9, 0xFFFF7070);
        // 警告文本按对话框宽度自动换行
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.translatable("create_cargo_dispatch.company.disband_warning"), DLG_W - 20);
        int lineY = py + 26;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawCenteredString(font, line, px + DLG_W / 2, lineY, 0xFFE0E0E0);
            lineY += 10;
        }
        int btnY = py + DLG_H - 22;
        int bx1 = px + DLG_BTN_PAD;
        int bx2 = px + DLG_W - DLG_BTN_PAD - DLG_BTN_W;
        drawButton(g, font, mouseX, mouseY, bx1, btnY,
                Component.translatable("create_cargo_dispatch.company.confirm"), true);
        drawButton(g, font, mouseX, mouseY, bx2, btnY,
                Component.translatable("create_cargo_dispatch.company.cancel"), false);
    }

    /** 命中判定：确认/取消按钮或被遮罩吞掉（坐标与 render 完全一致） */
    public static ClickResult click(int leftPos, int topPos, int imageWidth, double mouseX, double mouseY) {
        int px = leftPos + (imageWidth - DLG_W) / 2;
        int py = topPos + DLG_PY_REL;
        int btnY = py + DLG_H - 22;
        int bx1 = px + DLG_BTN_PAD;
        int bx2 = px + DLG_W - DLG_BTN_PAD - DLG_BTN_W;
        if (mouseY >= btnY && mouseY < btnY + DLG_BTN_H) {
            if (mouseX >= bx1 && mouseX < bx1 + DLG_BTN_W) return ClickResult.CONFIRM;
            if (mouseX >= bx2 && mouseX < bx2 + DLG_BTN_W) return ClickResult.DISMISS;
        }
        return ClickResult.NONE;
    }

    private static void drawButton(GuiGraphics g, Font font, int mouseX, int mouseY,
                                   int x, int y, Component label, boolean danger) {
        boolean hover = mouseX >= x && mouseX < x + DLG_BTN_W && mouseY >= y && mouseY < y + DLG_BTN_H;
        int color;
        if (danger) color = hover ? 0xFF8B3A3A : 0xFF5A2A2A;
        else color = hover ? 0xFF3A8B4E : 0xFF2A5A34;
        g.fill(x, y, x + DLG_BTN_W, y + DLG_BTN_H, color);
        g.renderOutline(x, y, DLG_BTN_W, DLG_BTN_H, 0xFF8B8B8B);
        g.drawCenteredString(font, label, x + DLG_BTN_W / 2, y + (DLG_BTN_H - 8) / 2 + 1, 0xFFFFFFFF);
    }
}
