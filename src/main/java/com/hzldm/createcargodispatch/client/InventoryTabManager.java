package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 背包标签页管理器
 *
 * 原理：
 *  - 在生存背包/订单页/连接页顶部渲染自制标签栏
 *  - 用 fill+outline 绘制标签框，避免纹理 UV 问题
 *  - 背包页：显示「背包」(选中) + 「货运」标签
 *  - 订单页：显示「背包」+ 「订单」(选中) + 「连接」标签
 *  - 连接页：显示「背包」+ 「订单」+ 「连接」(选中) 标签
 *
 * 布局：
 *  - 标签位置：屏幕左上角顶部 (leftPos+4, topPos-28)
 *  - 标签尺寸：28×32（原版标准）
 *  - 标签间隔：0px
 */
@EventBusSubscriber(modid = CreateCargoDispatch.MODID, value = Dist.CLIENT)
public final class InventoryTabManager {

    private static final int TAB_WIDTH = 28;
    private static final int TAB_HEIGHT = 32;
    private static final int TAB_OFFSET_X = 4;
    private static final int TAB_Y_ABOVE = -28;

    /** 背包图标 */
    private static final ItemStack INV_ICON = new ItemStack(Items.CRAFTING_TABLE);
    /** 订单图标 */
    private static final ItemStack ORDERS_ICON = new ItemStack(Items.BOOK);
    /** 连接图标 */
    private static final ItemStack LINK_ICON = new ItemStack(Items.COMPASS);
    /** 联合运输图标 */
    private static final ItemStack COMPANY_ICON = new ItemStack(Items.WRITABLE_BOOK);

    /** 标签颜色 */
    private static final int COLOR_SELECTED = 0xFFC8C8C8;
    private static final int COLOR_UNSELECTED = 0xFF6B6B6B;
    private static final int COLOR_BORDER = 0xFF2B2B2B;
    private static final int COLOR_TEXT = 0xFFFFFFFF;

    private InventoryTabManager() {
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        int guiLeft;
        int guiTop;
        TabType activeTab;

        if (screen instanceof InventoryScreen invScreen) {
            guiLeft = invScreen.getGuiLeft();
            guiTop = invScreen.getGuiTop();
            activeTab = TabType.INVENTORY;
        } else if (screen instanceof PlayerOrdersScreen ordersScreen) {
            guiLeft = ordersScreen.getGuiLeft();
            guiTop = ordersScreen.getGuiTop();
            activeTab = TabType.ORDERS;
        } else if (screen instanceof ConnectedStationsScreen connScreen) {
            guiLeft = connScreen.getGuiLeft();
            guiTop = connScreen.getGuiTop();
            activeTab = TabType.CONNECTED;
        } else if (screen instanceof CompanyScreen companyScreen) {
            guiLeft = companyScreen.getGuiLeft();
            guiTop = companyScreen.getGuiTop();
            activeTab = TabType.COMPANY;
        } else {
            return;
        }

        renderTabs(event, guiLeft, guiTop, activeTab);
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int guiLeft;
        int guiTop;
        TabType activeTab;

        if (screen instanceof InventoryScreen invScreen) {
            guiLeft = invScreen.getGuiLeft();
            guiTop = invScreen.getGuiTop();
            activeTab = TabType.INVENTORY;
        } else if (screen instanceof PlayerOrdersScreen ordersScreen) {
            guiLeft = ordersScreen.getGuiLeft();
            guiTop = ordersScreen.getGuiTop();
            activeTab = TabType.ORDERS;
        } else if (screen instanceof ConnectedStationsScreen connScreen) {
            guiLeft = connScreen.getGuiLeft();
            guiTop = connScreen.getGuiTop();
            activeTab = TabType.CONNECTED;
        } else if (screen instanceof CompanyScreen companyScreen) {
            guiLeft = companyScreen.getGuiLeft();
            guiTop = companyScreen.getGuiTop();
            activeTab = TabType.COMPANY;
        } else {
            return;
        }

        // 检查每个标签的点击
        TabType[] tabs = TabType.values();
        for (int i = 0; i < tabs.length; i++) {
            int tabX = guiLeft + TAB_OFFSET_X + i * TAB_WIDTH;
            int tabY = guiTop + TAB_Y_ABOVE;
            if (isMouseOverTab(event.getMouseX(), event.getMouseY(), tabX, tabY)) {
                if (tabs[i] != activeTab) {
                    switchToTab(mc, tabs[i], screen);
                }
                event.setCanceled(true);
                return;
            }
        }
    }

    /** 渲染所有标签 */
    private static void renderTabs(ScreenEvent.Render.Post event, int guiLeft, int guiTop, TabType activeTab) {
        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();
        TabType[] tabs = TabType.values();
        int mouseX = (int) event.getMouseX();
        int mouseY = (int) event.getMouseY();

        for (int i = 0; i < tabs.length; i++) {
            int tabX = guiLeft + TAB_OFFSET_X + i * TAB_WIDTH;
            int tabY = guiTop + TAB_Y_ABOVE;
            boolean selected = tabs[i] == activeTab;
            boolean hovered = isMouseOverTab(event.getMouseX(), event.getMouseY(), tabX, tabY);

            renderSingleTab(graphics, mc, tabX, tabY, tabs[i], selected, hovered, mouseX, mouseY);
        }
    }

    /** 渲染单个标签 */
    private static void renderSingleTab(GuiGraphics graphics, Minecraft mc,
                                         int x, int y, TabType tab, boolean selected, boolean hovered,
                                         int mouseX, int mouseY) {
        int bgColor = selected ? COLOR_SELECTED : (hovered ? 0xFF8A8A8A : COLOR_UNSELECTED);

        // 标签背景
        graphics.fill(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, bgColor);
        // 选中态底部填充背包同色，制造连接效果
        if (selected) {
            graphics.fill(x, y + TAB_HEIGHT - 4, x + TAB_WIDTH, y + TAB_HEIGHT, COLOR_SELECTED);
        }
        // 边框（顶部+左右，底部不画让选中态与背包连接）
        graphics.fill(x, y, x + TAB_WIDTH, y + 1, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + TAB_HEIGHT, COLOR_BORDER);
        graphics.fill(x + TAB_WIDTH - 1, y, x + TAB_WIDTH, y + TAB_HEIGHT, COLOR_BORDER);

        // 渲染图标（居中，选中态下移 2px）
        ItemStack icon = getTabIcon(tab);
        int iconX = x + (TAB_WIDTH - 16) / 2;
        int iconY = y + (selected ? 10 : 8);
        graphics.renderItem(icon, iconX, iconY);

        // 悬停提示
        if (hovered && !selected) {
            graphics.renderTooltip(mc.font, getTabTitle(tab), mouseX, mouseY);
        }
    }

    /**
     * 切换到指定标签
     * 原理：
     *  - 所有切换都不调用 onClose()，避免 setScreen(null) 释放鼠标导致归位
     *  - 先发送 ServerboundContainerClosePacket 关闭服务端菜单
     *  - INVENTORY：客户端直接 setScreen 切换
     *  - ORDERS/CONNECTED：服务端 openMenu 返回时 NeoForge 自动 setScreen（会先关闭旧 screen）
     */
    private static void switchToTab(Minecraft mc, TabType tab, Screen currentScreen) {
        // 发送关闭菜单包（不调用 onClose 避免 setScreen(null) 释放鼠标）
        if (currentScreen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(
                    containerScreen.getMenu().containerId));
        }
        switch (tab) {
            case INVENTORY -> mc.setScreen(new InventoryScreen(mc.player));
            case ORDERS -> PacketDistributor.sendToServer(new com.hzldm.createcargodispatch.network.OpenOrdersMenuPayload());
            case CONNECTED -> PacketDistributor.sendToServer(new com.hzldm.createcargodispatch.network.OpenConnectedStationsPayload());
            case COMPANY -> PacketDistributor.sendToServer(new com.hzldm.createcargodispatch.network.OpenCompanyMenuPayload());
        }
    }

    private static ItemStack getTabIcon(TabType tab) {
        return switch (tab) {
            case INVENTORY -> INV_ICON;
            case ORDERS -> ORDERS_ICON;
            case CONNECTED -> LINK_ICON;
            case COMPANY -> COMPANY_ICON;
        };
    }

    private static Component getTabTitle(TabType tab) {
        return switch (tab) {
            case INVENTORY -> Component.translatable("create_cargo_dispatch.tab.inventory");
            case ORDERS -> Component.translatable("create_cargo_dispatch.tab.cargo");
            case CONNECTED -> Component.translatable("create_cargo_dispatch.tab.connected");
            case COMPANY -> Component.translatable("create_cargo_dispatch.tab.company");
        };
    }

    /** 检测鼠标是否在标签有效区域内 */
    private static boolean isMouseOverTab(double mouseX, double mouseY, int tabX, int tabY) {
        return mouseX >= tabX && mouseX < tabX + TAB_WIDTH
                && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT - 4;
    }

    /** 标签类型 */
    private enum TabType {
        INVENTORY, ORDERS, CONNECTED, COMPANY
    }
}
