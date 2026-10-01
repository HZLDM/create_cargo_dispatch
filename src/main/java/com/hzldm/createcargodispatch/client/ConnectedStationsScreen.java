package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.menu.ConnectedStationsMenu;
import com.hzldm.createcargodispatch.network.SyncLinkagesPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 已连接站点页面（背包连接页）
 *
 * 原理：
 *  - 使用与 PlayerOrdersScreen 一致的深色自制背景
 *  - 显示所有已连接的货运站列表（类型 + 坐标）
 *  - 每个站点右侧有「🔔/🔕 提示开关」按钮 + 「断开」按钮
 *  - 页面底部有「一键全部开/关提示」总开关（与单站按钮联动，共用服务端下发的 Linkage 数据）
 *  - 顶部标签由 InventoryTabManager 渲染
 */
public class ConnectedStationsScreen extends AbstractContainerScreen<ConnectedStationsMenu> {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int ROW_COLOR_EVEN = 0xFF1F1F1F;
    private static final int ROW_COLOR_ODD = 0xFF262626;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static final int ITEMS_PER_PAGE = 8;
    private int scrollOffset = 0;

    /** 页对齐最大 offset：最后一页不足一页时，从页首开始单独显示，不与上一页后半段重叠 */
    private static int pageAlignedMax(int size, int perPage) {
        if (size <= 0) return 0;
        int pages = (size - 1) / perPage + 1;
        return Math.max(0, (pages - 1) * perPage);
    }

    /**
     * 在区间 [leftX, rightX] 内画一段文本，若超出宽度则省略号紧贴 rightX 左边界绘制，避免"左对齐+…"造成的右侧大段空白（视觉"缩进"）。
     *
     * @param graphics 绘图对象
     * @param text     原始文本
     * @param leftX    左边界（前缀起始 x，前缀到此为止）
     * @param rightX   右边界（省略号的右边缘不得超过此值，即省略号紧贴此边界左侧）
     * @param y        绘制 y
     * @param color    字体颜色
     */
    private void drawTextEllipsized(GuiGraphics graphics, String text, int leftX, int rightX, int y, int color) {
        if (leftX >= rightX) return;
        int maxW = rightX - leftX;
        if (maxW <= 0) return;
        int fullW = font.width(text);
        if (fullW <= maxW) {
            // 未超出：左对齐直接画（用户偏好左对齐阅读顺序，不做右对齐）
            graphics.drawString(font, Component.literal(text), leftX, y, color, false);
            return;
        }
        // 超出：省略号画在紧贴 rightX 左侧，前缀画在左边（省略号右边=右边界，无空隙→消除缩进）
        String ellipsis = "…";
        int ellipsisW = font.width(ellipsis);
        if (maxW <= ellipsisW) {
            // 空间极小，只画省略号，也靠右贴边
            graphics.drawString(font, Component.literal(ellipsis), rightX - ellipsisW, y, color, false);
            return;
        }
        int remain = maxW - ellipsisW; // 前缀可用宽度
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

    public ConnectedStationsScreen(ConnectedStationsMenu menu, Inventory playerInventory, Component title) {
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
            int max = pageAlignedMax(getMenu().getLinkages().size(), ITEMS_PER_PAGE);
            scrollOffset = Math.min(max, scrollOffset + ITEMS_PER_PAGE);
        }).bounds(leftPos + imageWidth - 18, topPos + imageHeight - 18, 12, 12).build());
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 页对齐边界修正：不足一页的数据从整页起点开始显示，单独成页不与上一页后半重叠
        int max = pageAlignedMax(getMenu().getLinkages().size(), ITEMS_PER_PAGE);
        if (scrollOffset > max) scrollOffset = max;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题
        graphics.drawString(font, Component.translatable("create_cargo_dispatch.tab.connected"),
                leftPos + 8, topPos + 6, TEXT_COLOR);
        // 分割线
        graphics.fill(leftPos + 8, topPos + 16, leftPos + imageWidth - 24, topPos + 17, 0xFF666666);

        List<SyncLinkagesPayload.LinkageEntry> linkages = getMenu().getLinkages();
        int total = linkages.size();
        int totalPages = (total <= 0) ? 1 : ((total - 1) / ITEMS_PER_PAGE + 1);
        int currentPage1 = (scrollOffset / ITEMS_PER_PAGE) + 1;
        if (totalPages < 1) totalPages = 1;
        if (currentPage1 < 1) currentPage1 = 1;
        if (currentPage1 > totalPages) currentPage1 = totalPages;

        // 统计提示（左边：连接数+提示开关比例；右边：页数）
        int enabledCount = 0;
        for (SyncLinkagesPayload.LinkageEntry e : linkages) if (e.notifyEnabled()) enabledCount++;
        Component countLabel = Component.translatable("create_cargo_dispatch.linkage.connected_count", total)
                .append(Component.literal(String.format("  §7(§a§l%d§7/§r§b§l%d§7)", enabledCount, total)));
        drawPaginationHint(graphics, topPos + 20, countLabel,
                Component.translatable("create_cargo_dispatch.label.count_pages", currentPage1, totalPages));

        if (linkages.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.linkage.no_linkages"),
                    leftPos + imageWidth / 2, topPos + imageHeight / 2, 0xFF808080);
            return;
        }

        // ======================== 列表渲染 ========================
        // 布局（每行从左→右）：
        //   [站点短名] 文本区（悬停显示坐标/完整信息） | 📋 订单 | 🔔/🔕 按钮 | 断开 按钮
        int rowY = topPos + 34;
        final int rowHeight = 16;
        final int rowWidth = imageWidth - 32;

        final int DISCONNECT_BTN_W = 32;
        final int NOTIFY_BTN_W = 22;
        final int VIEW_ORDERS_BTN_W = 28;

        int start = scrollOffset;
        int end = Math.min(linkages.size(), start + ITEMS_PER_PAGE);

        // 悬停 Tooltip：命中站点信息区（按钮区不触发）时，存储待绘制组件
        java.util.List<Component> pendingTooltip = null;
        int tooltipMouseX = mouseX, tooltipMouseY = mouseY;

        for (int i = start; i < end; i++) {
            // 底部要留空间放"一键总开关"，限制列表最多渲染到 topPos+imageHeight-32
            if (rowY > topPos + imageHeight - 34) break;
            SyncLinkagesPayload.LinkageEntry link = linkages.get(i);
            int rowX = leftPos + 8;
            int rowColor = (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD;
            graphics.fill(rowX, rowY, rowX + rowWidth, rowY + rowHeight - 2, rowColor);

            // —— 先从右向左计算三个按钮的 x（保证边界固定）
            int disBtnX = rowX + rowWidth - DISCONNECT_BTN_W - 2;
            int btnY = rowY + 1;
            int notifyBtnX = disBtnX - NOTIFY_BTN_W - 2;
            int viewBtnX = notifyBtnX - VIEW_ORDERS_BTN_W - 2;

            // 站点信息：只显示 [短名]（视觉干净），完整坐标+状态放到 Tooltip；
            // 标题改用 station_short 翻译（不带"货物"后缀），避免和内核使用的 station_type.* 冲突
            String fullName = getStationTypeShortName(link.sourceType());
            String shortName = fullName;
            int typeColor = getTypeColor(link.sourceType());
            String label = String.format("[%s]", shortName);
            graphics.drawString(font, Component.literal(label), rowX + 4, rowY + 4, typeColor, false);

            // 站点信息热区：[rowX+4, viewBtnX-4] × [rowY, rowY + rowHeight-2]，且不能落在 3 个按钮 y 内
            int infoX1 = rowX + 4, infoX2 = viewBtnX - 4;
            int infoY1 = rowY, infoY2 = rowY + rowHeight - 2;
            boolean inInfoRect = mouseX >= infoX1 && mouseX < infoX2 && mouseY >= infoY1 && mouseY < infoY2;
            boolean inBtnRect = (mouseX >= viewBtnX && mouseX < disBtnX + DISCONNECT_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12);
            if (inInfoRect && !inBtnRect) {
                java.util.ArrayList<Component> lines = new java.util.ArrayList<>(5);
                lines.add(Component.literal("§6" + fullName));
                lines.add(Component.literal("§7坐标：§fX=" + link.sourceX() + "  Y=" + link.sourceY() + "  Z=" + link.sourceZ()));
                lines.add(link.notifyEnabled()
                        ? Component.literal("§a消息提示：已开启")
                        : Component.literal("§7消息提示：已关闭（🔕按钮可开启）"));
                lines.add(Component.literal(""));
                lines.add(Component.literal("§b提示：点击『📋』按钮可远程查看本站未接订单（只读）"));
                pendingTooltip = lines;
            }

            // —— 断开按钮（最右）
            boolean disHovered = mouseX >= disBtnX && mouseX < disBtnX + DISCONNECT_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12;
            int disColor = disHovered ? 0xFF8B3A3A : 0xFF5A2A2A;
            graphics.fill(disBtnX, btnY, disBtnX + DISCONNECT_BTN_W, btnY + 12, disColor);
            graphics.renderOutline(disBtnX, btnY, DISCONNECT_BTN_W, 12, BORDER_COLOR);
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.linkage.disconnect"),
                    disBtnX + DISCONNECT_BTN_W / 2, btnY + 2, TEXT_COLOR);

            // —— 🔔/🔕 提示开关
            boolean notifyHovered = mouseX >= notifyBtnX && mouseX < notifyBtnX + NOTIFY_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12;
            boolean on = link.notifyEnabled();
            int notifyBg = on
                    ? (notifyHovered ? 0xFF3AAA5A : 0xFF2A7A3A)
                    : (notifyHovered ? 0xFF707070 : 0xFF4A4A4A);
            graphics.fill(notifyBtnX, btnY, notifyBtnX + NOTIFY_BTN_W, btnY + 12, notifyBg);
            graphics.renderOutline(notifyBtnX, btnY, NOTIFY_BTN_W, 12, BORDER_COLOR);
            String notifyLabel = on ? "🔔" : "🔕";
            graphics.drawCenteredString(font, Component.literal(notifyLabel),
                    notifyBtnX + NOTIFY_BTN_W / 2, btnY + 2, 0xFFFFFFFF);

            // —— 📋 订单查看（只读远程查看，点击发 RequestStationOrdersPayload 给服务端）
            boolean viewHovered = mouseX >= viewBtnX && mouseX < viewBtnX + VIEW_ORDERS_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12;
            int viewBg = viewHovered ? 0xFF4A6FA0 : 0xFF3A4F70;
            graphics.fill(viewBtnX, btnY, viewBtnX + VIEW_ORDERS_BTN_W, btnY + 12, viewBg);
            graphics.renderOutline(viewBtnX, btnY, VIEW_ORDERS_BTN_W, 12, BORDER_COLOR);
            graphics.drawCenteredString(font, Component.literal("📋"),
                    viewBtnX + VIEW_ORDERS_BTN_W / 2, btnY + 2, 0xFFFFFFFF);

            rowY += rowHeight;
        }

        // ======================== 底部：一键总开关 + 说明 ========================
        // allOn = 当前所有已连接站点都开启了提示 → 按钮文案 = "关闭所有提示"（点击→发送 false）
        // 否则 → 按钮文案 = "开启所有提示"（点击→发送 true）
        boolean allOn = !linkages.isEmpty() && enabledCount == linkages.size();
        boolean anyOn = enabledCount > 0;

        int globalBtnX = leftPos + 8;
        int globalBtnY = topPos + imageHeight - 22;
        int globalBtnW = 92;
        int globalBtnH = 14;
        boolean gHovered = mouseX >= globalBtnX && mouseX < globalBtnX + globalBtnW
                && mouseY >= globalBtnY && mouseY < globalBtnY + globalBtnH;

        // 按钮颜色：若接下来点击"开启所有"→绿基色；若接下来点击"关闭所有"→红基色
        int gBg;
        String gLabel;
        if (allOn) {
            // 当前全开 → 点一下 = 全部关闭
            gBg = gHovered ? 0xFFB03A3A : 0xFF8A2A2A;
            gLabel = Component.translatable("create_cargo_dispatch.linkage.mute_all").getString();
        } else {
            // 当前存在至少一个关闭 → 点一下 = 全部开启（无论 anyOn）
            gBg = gHovered ? 0xFF3AAA5A : 0xFF2A7A3A;
            gLabel = Component.translatable("create_cargo_dispatch.linkage.notify_all").getString();
        }
        graphics.fill(globalBtnX, globalBtnY, globalBtnX + globalBtnW, globalBtnY + globalBtnH, gBg);
        graphics.renderOutline(globalBtnX, globalBtnY, globalBtnW, globalBtnH, BORDER_COLOR);
        graphics.drawCenteredString(font, Component.literal(gLabel),
                globalBtnX + globalBtnW / 2, globalBtnY + 3, 0xFFFFFFFF);

        // 右侧说明：开关作用范围解释（"控制聊天栏提示+音效"）
        Component tip = Component.translatable("create_cargo_dispatch.linkage.notify_tip");
        int tipX = globalBtnX + globalBtnW + 6;
        int tipY = globalBtnY + 3;
        if (tipX + font.width(tip) <= leftPos + imageWidth - 24) {
            graphics.drawString(font, tip, tipX, tipY, 0xFFB0B0B0, false);
        } else if (anyOn) {
            // 放不下就改渲染一个极简"X/Y已开"的精简状态
            Component miniState = Component.literal(String.format("%d/%d", enabledCount, linkages.size()));
            graphics.drawString(font, miniState, tipX, tipY, 0xFFB0B0B0, false);
        }

        // ======================== 悬停站点信息时显示详细 Tooltip ========================
        // 信息分层：画面只显示关键字段（站点短名），坐标/完整类型/提示状态放到 Tooltip 里，
        // 避免行内坐标过长与按钮重叠（SRP：画面管呈现，Tooltip 管详细信息按需查看）
        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            graphics.renderTooltip(font, pendingTooltip, java.util.Optional.empty(), tooltipMouseX, tooltipMouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<SyncLinkagesPayload.LinkageEntry> linkages = getMenu().getLinkages();
        if (linkages == null) return super.mouseClicked(mouseX, mouseY, button);
        final int rowHeight = 16;
        final int rowWidth = imageWidth - 32;
        final int DISCONNECT_BTN_W = 32;
        final int NOTIFY_BTN_W = 22;
        final int VIEW_ORDERS_BTN_W = 28;

        int rowY = topPos + 34;
        int start = scrollOffset;
        int end = Math.min(linkages.size(), start + ITEMS_PER_PAGE);

        for (int i = start; i < end; i++) {
            if (rowY > topPos + imageHeight - 34) break;
            int rowX = leftPos + 8;
            int btnY = rowY + 1;

            // 1) 断开按钮（最右）
            int disBtnX = rowX + rowWidth - DISCONNECT_BTN_W - 2;
            if (mouseX >= disBtnX && mouseX < disBtnX + DISCONNECT_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12) {
                getMenu().disconnectStation(i);
                return true;
            }

            // 2) 🔔/🔕 提示开关按钮
            int notifyBtnX = disBtnX - NOTIFY_BTN_W - 2;
            if (mouseX >= notifyBtnX && mouseX < notifyBtnX + NOTIFY_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12) {
                SyncLinkagesPayload.LinkageEntry link = linkages.get(i);
                // 当前状态→点击取反。服务端收到后回传新的 SyncLinkagesPayload 让 UI 联动。
                boolean nextEnabled = !link.notifyEnabled();
                getMenu().toggleStationNotify(i, nextEnabled);
                return true;
            }

            // 3) 📋 订单查看（只读）：动态菜单/纯视图模式
            //    原理（不再是「刷新→再显示」，而是「直接显示共享缓存视图」）：
            //     服务端每 2 秒（订单变化时提前）用 SyncOrdersPayload 按类型过滤推 PENDING 订单到
            //     ClientCargoCache.ORDERS 全局共享池。点📋按钮时：
            //       ① 仅在元信息层设置「当前 viewer 正在看哪个站的坐标/类型」（不复制订单，不过滤列表，不发请求）
            //       ② 直接 setScreen 打开 StationOrdersViewerScreen
            //       ③ StationOrdersViewerScreen 每次 render 都直接从 ClientCargoCache.getOrdersForStation(站坐标)
            //         实时过滤拿列表 —— 与 CargoGeneratorScreen 订单 Tab 共用同一数据源、同一 tick 更新，
            //         行内的订单剩余 ⏱mm:ss 倒计时也完全相同（同一个 expireAtGameTime - now 公式）。
            int viewBtnX = notifyBtnX - VIEW_ORDERS_BTN_W - 2;
            if (mouseX >= viewBtnX && mouseX < viewBtnX + VIEW_ORDERS_BTN_W
                    && mouseY >= btnY && mouseY < btnY + 12) {
                SyncLinkagesPayload.LinkageEntry link = linkages.get(i);
                // 仅设置"看哪个站"的元信息；订单列表由 Screen 在渲染时实时查全局共享缓存，不做任何"刷新"动作。
                ClientCargoCache.setViewerStation(
                        link.sourceX(), link.sourceY(), link.sourceZ(),
                        link.sourceType() != null ? link.sourceType() : "");
                if (minecraft != null) {
                    minecraft.setScreen(new StationOrdersViewerScreen());
                }
                return true;
            }
            rowY += rowHeight;
        }

        // 4) 底部"一键全部开/关提示"总开关
        boolean allOn = false;
        int enabledCount = 0;
        if (!linkages.isEmpty()) {
            for (SyncLinkagesPayload.LinkageEntry e : linkages) if (e.notifyEnabled()) enabledCount++;
            allOn = (enabledCount == linkages.size());
        }
        int globalBtnX = leftPos + 8;
        int globalBtnY = topPos + imageHeight - 22;
        int globalBtnW = 92;
        int globalBtnH = 14;
        if (mouseX >= globalBtnX && mouseX < globalBtnX + globalBtnW
                && mouseY >= globalBtnY && mouseY < globalBtnY + globalBtnH) {
            // 当前全开启 → 点一下全部关闭；否则全部开启
            boolean nextAllEnabled = !allOn;
            getMenu().toggleAllStationsNotify(nextAllEnabled);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = pageAlignedMax(getMenu().getLinkages().size(), ITEMS_PER_PAGE);
        if (scrollY > 0 && scrollOffset > 0) scrollOffset = Math.max(0, scrollOffset - ITEMS_PER_PAGE);
        else if (scrollY < 0 && scrollOffset < max) scrollOffset = Math.min(max, scrollOffset + ITEMS_PER_PAGE);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 分页提示：左=自定义标签，右=第P/T页 */
    private void drawPaginationHint(GuiGraphics g, int y, Component leftLabel, Component rightLabel) {
        int leftX = leftPos + 8;
        int rightX = leftPos + imageWidth - 24 - 2;
        int leftColor = 0xFFA0A0FF;
        int rightColor = 0xFFE8E8E8;
        g.drawString(font, leftLabel, leftX, y, leftColor, false);
        int rightW = font.width(rightLabel);
        g.drawString(font, rightLabel, rightX - rightW, y, rightColor, false);
    }

    /** 根据类型返回不同颜色 */
    private int getTypeColor(String typeId) {
        return switch (typeId) {
            case "lumber_yard" -> 0xFFA0854A;
            case "mine" -> 0xFF808090;
            case "farm" -> 0xFF70A85A;
            case "pasture" -> 0xFFD0C0A0;
            default -> 0xFFD0D0D0;
        };
    }

    /** 获取站点类型的中文名 */
    private String getStationTypeName(String typeId) {
        return Component.translatable("create_cargo_dispatch.station_type." + typeId).getString();
    }

    /** 站点短名（不带"货物"二字），用于 UI 拥挤场景减少字符占用 */
    private String getStationTypeShortName(String typeId) {
        return Component.translatable("create_cargo_dispatch.station_short." + typeId).getString();
    }
}
