package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.item.SelectableStationItem;
import com.hzldm.createcargodispatch.item.StationItemConverter;
import com.hzldm.createcargodispatch.network.ConvertStationItemPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 通用货运方块「类型选择页」（客户端）
 *
 * 原理：
 *  - 玩家手持通用 货运站/生成器/检测器 物品潜行+右键时打开
 *  - 列出 5 种工业类型，点击某行 → 发送 ConvertStationItemPayload 给服务端转换主手物品
 *  - 纯客户端 Screen（无 Menu），关闭后游戏内物品由服务端替换
 */
public class StationTypeSelectScreen extends Screen {

    /** 可选择的类型（不含 GENERIC） */
    private static final StationType[] TYPES = {
            StationType.LUMBER_YARD, StationType.MINE, StationType.FARM,
            StationType.PASTURE, StationType.METALLURGY
    };

    private static final int ROW_HEIGHT = 26;
    private static final int PANEL_WIDTH = 180;
    private static final int PANEL_HEIGHT = 24 + TYPES.length * ROW_HEIGHT + 12;

    private final SelectableStationItem.ItemKind kind;

    public StationTypeSelectScreen(SelectableStationItem.ItemKind kind) {
        super(Component.translatable("create_cargo_dispatch.select.title"));
        this.kind = kind;
    }

    /** 客户端主线程打开选择页（由 SelectableStationItem 在客户端分支调用） */
    public static void open(SelectableStationItem.ItemKind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new StationTypeSelectScreen(kind));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int panelTop = Math.max(20, (this.height - PANEL_HEIGHT) / 2);

        // 面板背景
        guiGraphics.fill(centerX - PANEL_WIDTH / 2 - 4, panelTop - 24,
                centerX + PANEL_WIDTH / 2 + 4, panelTop + PANEL_HEIGHT + 4, 0xCC181818);
        // 标题
        guiGraphics.drawCenteredString(this.font, this.title, centerX, panelTop - 18, 0xFFFFFFFF);

        // 5 行类型按钮
        for (int i = 0; i < TYPES.length; i++) {
            StationType type = TYPES[i];
            int rowY = panelTop + i * ROW_HEIGHT;
            int left = centerX - PANEL_WIDTH / 2;
            int right = centerX + PANEL_WIDTH / 2;
            boolean hovered = mouseX >= left && mouseX <= right && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 4;
            // 行背景
            guiGraphics.fill(left, rowY, right, rowY + ROW_HEIGHT - 4,
                    hovered ? 0xCC4A6B9A : 0xAA333333);
            // 图标
            guiGraphics.renderItem(new ItemStack(StationItemConverter.resolve(kind, type)), left + 4, rowY + 3);
            // 类型名称（短名，如 "伐木场"）
            guiGraphics.drawString(this.font, Component.translatable(type.getShortTranslationKey()),
                    left + 26, rowY + 8, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int centerX = this.width / 2;
        int panelTop = Math.max(20, (this.height - PANEL_HEIGHT) / 2);
        if (button == 0) {
            for (int i = 0; i < TYPES.length; i++) {
                int rowY = panelTop + i * ROW_HEIGHT;
                int left = centerX - PANEL_WIDTH / 2;
                int right = centerX + PANEL_WIDTH / 2;
                if (mouseX >= left && mouseX <= right && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 4) {
                    StationType type = TYPES[i];
                    // 发送转换请求，服务端校验并替换主手物品
                    PacketDistributor.sendToServer(new ConvertStationItemPayload(kind.name(), type.getId()));
                    this.onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
