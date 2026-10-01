package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.menu.PlayerOrdersMenu;
import com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 玩家货运订单页（背包订单标签）
 *
 * 原理：
 *  - 使用与深色自制纹理背景
 *  - 只显示活跃订单列表（已连接站点在独立页面）
 *  - 每个订单右侧有「放弃」按钮
 *  - 顶部标签由 InventoryTabManager 渲染
 */
public class PlayerOrdersScreen extends AbstractContainerScreen<PlayerOrdersMenu> {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int ROW_COLOR_EVEN = 0xFF1F1F1F;
    private static final int ROW_COLOR_ODD = 0xFF262626;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static final int ITEMS_PER_PAGE = 8;
    private int scrollOffset = 0;

    /** 页对齐最大 offset：最后一页不满时从 (totalPages-1)*PAGE 开始，单独显示不重叠 */
    private static int pageAlignedMax(int size, int perPage) {
        if (size <= 0) return 0;
        int pages = (size - 1) / perPage + 1;
        return Math.max(0, (pages - 1) * perPage);
    }

    public PlayerOrdersScreen(PlayerOrdersMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelX = Integer.MAX_VALUE;
        this.inventoryLabelY = Integer.MAX_VALUE;
    }

    @Override
    protected void init() {
        super.init();
        // 滚动按钮（按页翻）
        addRenderableWidget(Button.builder(Component.literal("↑"), b -> {
            scrollOffset = Math.max(0, scrollOffset - ITEMS_PER_PAGE);
        }).bounds(leftPos + imageWidth - 18, topPos + 18, 12, 12).build());

        addRenderableWidget(Button.builder(Component.literal("↓"), b -> {
            int max = pageAlignedMax(getMenu().getActiveOrders().size(), ITEMS_PER_PAGE);
            scrollOffset = Math.min(max, scrollOffset + ITEMS_PER_PAGE);
        }).bounds(leftPos + imageWidth - 18, topPos + imageHeight - 18, 12, 12).build());
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 页对齐边界修正：最后一页不足 8 条时，不从 8-N 开始（那样会重叠上一页后半段），而是从页首开始
        int max = pageAlignedMax(getMenu().getActiveOrders().size(), ITEMS_PER_PAGE);
        if (scrollOffset > max) scrollOffset = max;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 深色自制背景
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题
        graphics.drawString(font, Component.translatable("create_cargo_dispatch.tab.cargo"),
                leftPos + 8, topPos + 6, TEXT_COLOR);
        // 分割线
        graphics.fill(leftPos + 8, topPos + 16, leftPos + imageWidth - 24, topPos + 17, 0xFF666666);

        List<SyncActiveOrdersPayload.ActiveOrderEntry> activeOrders = getMenu().getActiveOrders();
        int total = activeOrders.size();
        int totalPages = (total <= 0) ? 1 : ((total - 1) / ITEMS_PER_PAGE + 1);
        int currentPage1 = (scrollOffset / ITEMS_PER_PAGE) + 1;
        if (totalPages < 1) totalPages = 1;
        if (currentPage1 < 1) currentPage1 = 1;
        if (currentPage1 > totalPages) currentPage1 = totalPages;

        // 统计提示：左=共N条，右=第P/T页
        drawPaginationHint(graphics, topPos + 20,
                Component.translatable("create_cargo_dispatch.label.count_orders", total),
                Component.translatable("create_cargo_dispatch.label.count_pages", currentPage1, totalPages));

        if (activeOrders.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.order.no_active"),
                    leftPos + imageWidth / 2, topPos + imageHeight / 2, 0xFF808080);
            return;
        }

        // 活跃订单列表
        int rowY = topPos + 34;
        int rowHeight = 16;
        int rowWidth = imageWidth - 32;

        int start = scrollOffset;
        int end = Math.min(activeOrders.size(), start + ITEMS_PER_PAGE);

        for (int i = start; i < end; i++) {
            if (rowY > topPos + imageHeight - 20) break;
            SyncActiveOrdersPayload.ActiveOrderEntry order = activeOrders.get(i);
            int rowX = leftPos + 8;
            int rowColor = (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD;
            graphics.fill(rowX, rowY, rowX + rowWidth, rowY + rowHeight - 2, rowColor);

            // 订单信息：#ID  物品名×数量
            String orderIdShort = order.orderId().substring(0, Math.min(8, order.orderId().length()));
            Component orderInfo = Component.literal(String.format("#%s  %s×%d",
                    orderIdShort, getItemName(order.cargoItemId()), order.cargoCount()));
            graphics.drawString(font, orderInfo, rowX + 4, rowY + 4, TEXT_COLOR);

            // 放弃按钮（右侧）
            int btnX = rowX + rowWidth - 36;
            int btnY = rowY + 1;
            boolean btnHovered = mouseX >= btnX && mouseX < btnX + 32
                    && mouseY >= btnY && mouseY < btnY + 12;
            int btnColor = btnHovered ? 0xFF8B3A3A : 0xFF5A2A2A;
            graphics.fill(btnX, btnY, btnX + 32, btnY + 12, btnColor);
            graphics.renderOutline(btnX, btnY, 32, 12, BORDER_COLOR);
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.order.abandon"),
                    btnX + 16, btnY + 2, TEXT_COLOR);

            rowY += rowHeight;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 检查放弃按钮点击
        List<SyncActiveOrdersPayload.ActiveOrderEntry> activeOrders = getMenu().getActiveOrders();
        int rowY = topPos + 34;
        int rowHeight = 16;
        int rowWidth = imageWidth - 32;

        int start = scrollOffset;
        int end = Math.min(activeOrders.size(), start + ITEMS_PER_PAGE);

        for (int i = start; i < end; i++) {
            if (rowY > topPos + imageHeight - 20) break;
            int btnX = leftPos + 8 + rowWidth - 36;
            int btnY = rowY + 1;
            if (mouseX >= btnX && mouseX < btnX + 32
                    && mouseY >= btnY && mouseY < btnY + 12) {
                getMenu().abandonOrder(i);
                return true;
            }
            rowY += rowHeight;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = pageAlignedMax(getMenu().getActiveOrders().size(), ITEMS_PER_PAGE);
        if (scrollY > 0 && scrollOffset > 0) scrollOffset = Math.max(0, scrollOffset - ITEMS_PER_PAGE);
        else if (scrollY < 0 && scrollOffset < max) scrollOffset = Math.min(max, scrollOffset + ITEMS_PER_PAGE);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 分页提示：左=共N条，右=第P/T页 */
    private void drawPaginationHint(GuiGraphics g, int y, Component leftLabel, Component rightLabel) {
        int leftX = leftPos + 8;
        int rightX = leftPos + imageWidth - 24 - 2;
        int leftColor = 0xFFA0FFA0;
        int rightColor = 0xFFE8E8E8;
        g.drawString(font, leftLabel, leftX, y, leftColor, false);
        int rightW = font.width(rightLabel);
        g.drawString(font, rightLabel, rightX - rightW, y, rightColor, false);
    }

    /** 获取物品名称 */
    private String getItemName(String itemId) {
        try {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.parse(itemId));
            return new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
        } catch (Throwable t) {
            return itemId;
        }
    }
}
