package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * 背包已连接站点 → 点击「📋 订单」按钮打开的**只读**订单查看页（纯共享缓存视图，无刷新机制）
 *
 * 只读保证：
 *  - 不渲染接单按钮 / 提交 Tab / 联络线按钮 / 自动提交开关
 *  - 不响应任何接单、提交操作的点击（mouseClicked 只处理滚动条）
 *  - 关闭页面时自动清空 ClientCargoCache 里的 viewer 站元信息，避免下一次打开闪旧站
 *
 * 与货运站现场 UI「完全共享同一数据源 + 同一更新节奏 + 同一剩余时间计算」：
 *  - 订单列表：每次渲染直接从 {@link ClientCargoCache#getOrdersForStation(BlockPos)} 读取，
 *    内部是从全局 ORDERS（CargoGeneratorScreen 现场 UI 也读这份）实时按站坐标过滤，
 *    无中间缓存、不保存快照、不做"刷新"动作 → 永远和现场 UI 看到的集合内容 + 行内倒计时值一致。
 *  - 更新触发：服务端 SyncOrdersPayload 广播每 2 秒 + 订单变化时提前触发 → 写入 ORDERS 全局共享池
 *    → StationOrdersViewerScreen 和 CargoGeneratorScreen 在同一个 client render tick 内看到相同的新列表。
 *  - 行内剩余时间：同一订单行 expireAtGameTime - level.getGameTime() 公式，两个 UI 算出的 ⏱mm:ss 100% 一致。
 *
 * UI 风格：与 CargoGeneratorScreen 一致（深色 220×232 背景 + 同款订单渲染），便于玩家"在哪看订单都一样"
 */
public class StationOrdersViewerScreen extends Screen {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int ROW_COLOR_EVEN = 0xFF1F1F1F;
    private static final int ROW_COLOR_ODD = 0xFF262626;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    /** 每页订单数（与货运站 UI 一致） */
    private static final int ENTRIES_PER_PAGE = 5;

    /** 页对齐最大 offset：最后一页不满时单独显示起始索引，避免与上一页重叠 */
    private static int pageAlignedMax(int size, int perPage) {
        if (size <= 0) return 0;
        int pages = (size - 1) / perPage + 1;
        return Math.max(0, (pages - 1) * perPage);
    }

    /** 订单列表页宽高（和 CargoGeneratorScreen 相同，视觉统一） */
    private static final int IMAGE_WIDTH = 220;
    private static final int IMAGE_HEIGHT = 232;

    /** 当前界面左上角坐标（渲染时赋值） */
    private int leftPos;
    private int topPos;

    /** 滚动偏移 */
    private int scrollOffset = 0;

    /** 当前查看的站坐标（每帧 tick 开始时重新从 ClientCargoCache 同步，避免和缓存不一致） */
    private BlockPos stationPos;
    /** 当前查看的站类型 ID */
    private String stationTypeId;

    public StationOrdersViewerScreen() {
        // 标题：稍后在渲染里根据站类型动态显示（含短名+坐标）
        super(Component.translatable("create_cargo_dispatch.station_viewer.title"));
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - IMAGE_WIDTH) / 2;
        this.topPos = (this.height - IMAGE_HEIGHT) / 2;
        this.stationPos = ClientCargoCache.getViewerStationPos();
        this.stationTypeId = ClientCargoCache.getViewerStationTypeId();

        // 上下滚动按钮（按页翻）
        addRenderableWidget(Button.builder(Component.literal("↑"), b -> {
            if (scrollOffset > 0) scrollOffset = Math.max(0, scrollOffset - ENTRIES_PER_PAGE);
        }).bounds(leftPos + IMAGE_WIDTH - 24, topPos + 8, 16, 16).build());

        addRenderableWidget(Button.builder(Component.literal("↓"), b -> {
            int max = pageAlignedMax(ClientCargoCache.getOrdersForStation(stationPos).size(), ENTRIES_PER_PAGE);
            if (scrollOffset < max) scrollOffset = Math.min(max, scrollOffset + ENTRIES_PER_PAGE);
        }).bounds(leftPos + IMAGE_WIDTH - 24, topPos + IMAGE_HEIGHT - 24, 16, 16).build());

        // 返回按钮：回到「已连接站点」页，避免每次按 ESC 后重新打开背包再找站点
        addRenderableWidget(Button.builder(
                Component.translatable("create_cargo_dispatch.station_viewer.back"), b -> {
            ClientCargoCache.clearStationViewerOrders();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.hzldm.createcargodispatch.network.OpenConnectedStationsPayload());
        }).bounds(leftPos + 8, topPos + IMAGE_HEIGHT - 23, 64, 14).build());
    }

    @Override
    public void tick() {
        super.tick();
        // 每帧同步一次站字段（正常情况下一直是同一个站，防止 cache 异常重写后不一致）
        this.stationPos = ClientCargoCache.getViewerStationPos();
        this.stationTypeId = ClientCargoCache.getViewerStationTypeId();

        // 滚动边界修正（页对齐：最后一页不满时单独显示，避免回拉到 size-PAGE 导致重叠上一页后半段）
        int max = pageAlignedMax(ClientCargoCache.getOrdersForStation(stationPos).size(), ENTRIES_PER_PAGE);
        if (scrollOffset > max) scrollOffset = max;

        // —— 不再有任何兜底过滤/写缓存逻辑：
        //    渲染时直接从全局 ORDERS 实时过滤，本身就是最新状态，不需要缓存同步步骤。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景（深色半透明：Screen 没有容器 Screen 的模糊层遮罩，自己画更舒服一点）
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(leftPos, topPos, leftPos + IMAGE_WIDTH, topPos + IMAGE_HEIGHT, BG_COLOR);
        graphics.renderOutline(leftPos, topPos, IMAGE_WIDTH, IMAGE_HEIGHT, BORDER_COLOR);

        // ===== 标题：📋 {站点短名} 未接订单 =====
        String shortName = (stationTypeId != null && !stationTypeId.isEmpty())
                ? Component.translatable("create_cargo_dispatch.station_short." + stationTypeId).getString()
                : "未知站点";
        graphics.drawCenteredString(font,
                Component.literal("📋 " + shortName + " 未接订单"),
                leftPos + IMAGE_WIDTH / 2, topPos + 6, 0xFFFFFFA0);

        // ===== 类型 + 坐标标签 =====
        int typeColor = getTypeColor(stationTypeId);
        graphics.drawCenteredString(font,
                Component.literal("类型：").append(Component.translatable(
                        "create_cargo_dispatch.station_type." + (stationTypeId != null ? stationTypeId : "generic")))
                        .withStyle(s -> s.withColor(typeColor)),
                leftPos + IMAGE_WIDTH / 2, topPos + 18, TEXT_COLOR);

        String posTxt = "坐标：X=" + (stationPos != null ? stationPos.getX() : 0)
                + "  Y=" + (stationPos != null ? stationPos.getY() : 0)
                + "  Z=" + (stationPos != null ? stationPos.getZ() : 0);
        graphics.drawCenteredString(font, Component.literal(posTxt),
                leftPos + IMAGE_WIDTH / 2, topPos + 28, 0xFFCCCCCC);

        // 分割线（已移除只读提示横幅，列表整体上移 10 像素，内容更紧凑）
        graphics.fill(leftPos + 8, topPos + 40, leftPos + IMAGE_WIDTH - 24, topPos + 41, 0xFF666666);

        // ===== 订单列表（完全复用 CargoGeneratorScreen 渲染风格，但无接单按钮） =====
        List<Component> tooltip = renderOrdersList(graphics, mouseX, mouseY);

        // 渲染所有通过 addRenderableWidget 注册的控件（右上角 ↑、右下角 ↓ 两个翻页按钮）。
        // 说明：StationOrdersViewerScreen 继承 Screen（而非 AbstractContainerScreen），
        // 若调用 super.render() 会重复触发 renderBackground 叠加遮罩层；因此改为手动遍历 renderables
        // 只画按钮（这正是 super.render 里对控件执行的关键动作）。
        this.renderables.forEach(w -> w.render(graphics, mouseX, mouseY, partialTick));

        // 底部不再画任何信息——与 CargoGeneratorScreen 现场 UI 一致（现场 UI 底部也没有文字）。
        // 订单行内的 ⏱mm:ss 是每秒都会自然减少的（只要 gameTime 在推进，见 isPauseScreen=false）。

        if (tooltip != null && !tooltip.isEmpty()) {
            graphics.renderTooltip(font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /**
     * 关键：重写为 false，打开本 Screen 时不暂停单人游戏。
     * 原理：
     *  Screen 基类 isPauseScreen() 默认返回 true。单人模式下，如果开的 Screen 返回 true，
     *  Minecraft 会调 Minecraft.pauseGame() → 整个 ClientLevel 停止 tick：
     *   - 玩家/实体不移动、天气/时间不推进、getGameTime() 永远不变；
     *   - 订单行内 ⏱ 倒计时"卡住不动"、底部计数器 0:00 一直不变、连鼠标都会被限制。
     *  这就是你说的"整个游戏冻结"现象。
     *  所以这里必须返回 false（和 CargoGeneratorScreen 容器屏行为一致），保证：
     *   - level tick 正常推进 → getGameTime() 每秒 +20 → 订单 ⏱ 倒计时每秒会跳；
     *   - 玩家可以继续走、跳、看视角，后台订单广播（SyncOrdersPayload）也能正常接收更新缓存。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ======================================================================
    // 订单列表渲染（只读版本：无接单按钮，整行都可悬停 Tooltip）
    // ======================================================================

    private List<Component> renderOrdersList(GuiGraphics graphics, int mouseX, int mouseY) {
        // 实时从全局共享缓存按站坐标过滤 → 每帧读都跟 CargoGeneratorScreen 同一个 ORDERS 集合一致。
        List<SyncOrdersPayload.OrderEntry> orders = ClientCargoCache.getOrdersForStation(stationPos);
        if (orders.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("create_cargo_dispatch.order.empty"),
                    leftPos + IMAGE_WIDTH / 2,
                    topPos + IMAGE_HEIGHT / 2,
                    0xFFAAAAAA);
            // 空列表也画分页提示
            drawPaginationHint(graphics, topPos + 42 + ENTRIES_PER_PAGE * 24 + 4,
                    Component.translatable("create_cargo_dispatch.label.count_orders", 0),
                    Component.translatable("create_cargo_dispatch.label.count_pages", 1, 1));
            return null;
        }
        List<Component> pendingTooltip = null;

        // 移除只读横幅后，列表起点从 +52 上移到 +42，与分割线（topPos+40）保持 2 像素间距
        int rowY = topPos + 42;
        int rowHeight = 24;
        int rowWidth = IMAGE_WIDTH - 32;
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            int orderIdx = scrollOffset + i;
            if (orderIdx >= orders.size()) break;
            SyncOrdersPayload.OrderEntry order = orders.get(orderIdx);
            int rowX = leftPos + 8;
            int rowColor = (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD;
            graphics.fill(rowX, rowY, rowX + rowWidth, rowY + rowHeight - 2, rowColor);

            // 只读版本：没有接单按钮占 48px → 第一行/第二行直接写满 rowX+4 到 rowX+rowWidth-4
            int leftMargin = rowX + 4;
            int rightMargin = rowX + rowWidth - 4;

            // ===== 第一行：#短号  物品×数量 =====
            String header = String.format("#%s  %s×%d",
                    order.orderId().substring(0, Math.min(8, order.orderId().length())),
                    getItemName(order.cargoItemId()),
                    order.cargoCount());
            drawTextEllipsized(graphics, header, leftMargin, rightMargin, rowY + 4, TEXT_COLOR);

            // ===== 第二行：[源→目标] + [⏱mm:ss] =====
            long nowTick = (minecraft != null && minecraft.level != null) ? minecraft.level.getGameTime() : 0L;
            int remainSec;
            if (order.expireAtGameTime() <= 0L) remainSec = -1;
            else {
                long remainTick = order.expireAtGameTime() - nowTick;
                remainSec = remainTick <= 0L ? 0 : (int) Math.ceil(remainTick / 20.0D);
            }
            String remainStr;
            int remainColor;
            if (remainSec < 0) { remainStr = "∞"; remainColor = 0xFFB0FFB0; }
            else if (remainSec == 0) {
                remainStr = String.format(java.util.Locale.ROOT, "%02d:%02d", 0, 0);
                remainColor = 0xFFFF3030;
            } else {
                int m = remainSec / 60;
                int s = remainSec % 60;
                remainStr = String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
                if (remainSec <= 30) remainColor = 0xFFFF3030;
                else if (remainSec <= 60) remainColor = 0xFFFFA800;
                else if (remainSec <= 180) remainColor = 0xFFF8E71C;
                else remainColor = 0xFFB0FFB0;
            }
            String remainTxt = "⏱ " + remainStr;
            int remainW = font.width(remainTxt);
            int remainLeftX = rightMargin - remainW;
            graphics.drawString(font, Component.literal(remainTxt), remainLeftX, rowY + 14, remainColor, false);

            String sourceTypeName = getStationTypeShortName(order.sourceStationType());
            String targetTypeName = getStationTypeShortName(order.targetStationType());
            String routeTxt = sourceTypeName + " → " + targetTypeName;
            drawTextEllipsized(graphics, routeTxt, leftMargin, remainLeftX - 4, rowY + 14, 0xFFA0FFA0);

            // ===== 悬停 Tooltip（只读版本整行都可以悬，没有按钮排除） =====
            boolean inInfo = mouseX >= rowX && mouseX < rowX + rowWidth
                    && mouseY >= rowY && mouseY < rowY + rowHeight - 2;
            if (inInfo) {
                java.util.ArrayList<Component> lines = new java.util.ArrayList<>(10);
                lines.add(Component.literal("§6订单 #" + order.orderId()));
                lines.add(Component.literal("§7货物：§f" + getItemName(order.cargoItemId()) + " × " + order.cargoCount()));
                lines.add(Component.literal("§7订单奖励：§6" + order.reward() + " 货运币"));
                lines.add(Component.literal(""));
                lines.add(Component.literal("§7起点站：§f" + getStationTypeShortName(order.sourceStationType())));
                lines.add(Component.literal("   X=" + order.startX() + "  Y=" + order.startY() + "  Z=" + order.startZ()));
                lines.add(Component.literal("§7终点站：§f" + getStationTypeShortName(order.targetStationType())));
                lines.add(Component.literal("   X=" + order.targetX() + "  Y=" + order.targetY() + "  Z=" + order.targetZ()));
                lines.add(Component.literal(""));
                if (remainSec < 0) {
                    lines.add(Component.literal("§a剩余时间：永久有效（不自动过期）"));
                } else if (remainSec == 0) {
                    lines.add(Component.literal("§c剩余时间：已过期（等待服务端移除）"));
                } else {
                    int m = remainSec / 60;
                    int s = remainSec % 60;
                    String color = (remainSec <= 30) ? "§c" : (remainSec <= 60 ? "§6" : (remainSec <= 180 ? "§e" : "§a"));
                    lines.add(Component.literal(color + "剩余时间：" + m + " 分 " + s + " 秒"));
                }
                pendingTooltip = lines;
            }
            rowY += rowHeight;
        }
        // 分页提示：列表下方画「共 N 条订单 · 第 P / T 页」
        int total = orders.size();
        int totalPages = (total <= 0) ? 1 : ((total - 1) / ENTRIES_PER_PAGE + 1);
        int currentPage1 = (scrollOffset / ENTRIES_PER_PAGE) + 1;
        if (totalPages < 1) totalPages = 1;
        if (currentPage1 < 1) currentPage1 = 1;
        if (currentPage1 > totalPages) currentPage1 = totalPages;
        drawPaginationHint(graphics, topPos + 42 + ENTRIES_PER_PAGE * 24 + 4,
                Component.translatable("create_cargo_dispatch.label.count_orders", total),
                Component.translatable("create_cargo_dispatch.label.count_pages", currentPage1, totalPages));
        return pendingTooltip;
    }

    @Override
    public void onClose() {
        // 关闭页：清空 viewer 站元信息（坐标/类型），避免下次打开其他站时闪旧标题/类型
        // 注：不涉及订单列表缓存——我们已经不再在查看层保存订单快照。
        ClientCargoCache.clearStationViewerOrders();
        super.onClose();
    }

    // ======================================================================
    // 工具：文本省略号 + 颜色/物品/短名（与 CargoGeneratorScreen 保持一致实现，视觉统一）
    // ======================================================================

    private void drawTextEllipsized(GuiGraphics graphics, String text, int leftX, int rightX, int y, int color) {
        if (leftX >= rightX) return;
        int maxW = rightX - leftX;
        if (maxW <= 0) return;
        int fullW = font.width(text);
        if (fullW <= maxW) {
            graphics.drawString(font, Component.literal(text), leftX, y, color, false);
            return;
        }
        String ellipsis = "…";
        int ellipsisW = font.width(ellipsis);
        if (maxW <= ellipsisW) {
            graphics.drawString(font, Component.literal(ellipsis), rightX - ellipsisW, y, color, false);
            return;
        }
        int remain = maxW - ellipsisW;
        int len = text.length();
        int lo = 0, hi = len, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int w = font.width(text.substring(0, mid));
            if (w <= remain) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        if (best > 0) {
            graphics.drawString(font, Component.literal(text.substring(0, best)), leftX, y, color, false);
        }
        graphics.drawString(font, Component.literal(ellipsis), rightX - ellipsisW, y, color, false);
    }

    private int getTypeColor(String typeId) {
        if (typeId == null) return 0xFFD0D0D0;
        return switch (typeId) {
            case "lumber_yard" -> 0xFFA0854A;
            case "mine" -> 0xFF808090;
            case "farm" -> 0xFF70A85A;
            case "pasture" -> 0xFFD0C0A0;
            default -> 0xFFD0D0D0;
        };
    }

    private String getStationTypeShortName(String typeId) {
        if (typeId == null || typeId.isEmpty()) return "通用";
        return Component.translatable("create_cargo_dispatch.station_short." + typeId).getString();
    }

    private String getItemName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "未知物品";
        try {
            ResourceLocation id = ResourceLocation.parse(itemId);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null) return itemId;
            return ItemStackRenderNameFallback.getName(item);
        } catch (Throwable t) {
            return itemId;
        }
    }

    /** 分页提示：左=共N条，右=第P/T页（与 CargoGeneratorScreen 同款样式） */
    private void drawPaginationHint(GuiGraphics g, int y, Component leftLabel, Component rightLabel) {
        int leftX = leftPos + 8;
        int rightX = leftPos + IMAGE_WIDTH - 24 - 2;
        int leftColor = 0xFFBBBBBB;
        int rightColor = 0xFFE8E8E8;
        g.drawString(font, leftLabel, leftX, y, leftColor, false);
        int rightW = font.width(rightLabel);
        g.drawString(font, rightLabel, rightX - rightW, y, rightColor, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = pageAlignedMax(ClientCargoCache.getOrdersForStation(stationPos).size(), ENTRIES_PER_PAGE);
        if (scrollY > 0 && scrollOffset > 0) scrollOffset = Math.max(0, scrollOffset - ENTRIES_PER_PAGE);
        else if (scrollY < 0 && scrollOffset < max) scrollOffset = Math.min(max, scrollOffset + ENTRIES_PER_PAGE);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 从 BuiltInRegistries 拿到 ItemStack 默认显示名（内置极小嵌套类，避免 StationOrdersViewerScreen 直接 import ItemStack 导致 ClassLoader 意外） */
    private static final class ItemStackRenderNameFallback {
        static String getName(Item item) {
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            return stack.getHoverName().getString();
        }
    }
}
