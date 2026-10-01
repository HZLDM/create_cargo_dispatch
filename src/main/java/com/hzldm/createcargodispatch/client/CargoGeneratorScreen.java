package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.menu.CargoGeneratorMenu;
import com.hzldm.createcargodispatch.network.ConnectStationPayload;
import com.hzldm.createcargodispatch.network.SyncOrdersPayload;
import com.hzldm.createcargodispatch.network.SyncSubmitListPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 货运站 UI 屏幕（支持「订单 Tab」/「提交 Tab」）
 *
 * 布局：
 *  - 图像：220×232（额外空间用于顶部 Tab 切换条 + 提交页自动提交开关）
 *  - 第 1 行（y=6）：标题
 *  - 第 2 行（y=18）：货运站类型标签
 *  - 第 3 行（y=28）：货运站编号标签
 *  - 第 4 行（y=38）：Tab 按钮（订单 / 提交）
 *  - Tab=订单：列表 y=52 开始，每行 24px
 *  - Tab=提交：
 *      * y=52：自动提交开关 + 刷新按钮
 *      * y=70：货箱列表，每行 24px
 */
public class CargoGeneratorScreen extends AbstractContainerScreen<CargoGeneratorMenu> {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int ROW_COLOR_EVEN = 0xFF1F1F1F;
    private static final int ROW_COLOR_ODD = 0xFF262626;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TAB_COLOR_SELECTED = 0xFF4A9D4A;
    private static final int TAB_COLOR_UNSELECTED = 0xFF404040;

    /** 每页条目数（订单/提交 共用） */
    private static final int ENTRIES_PER_PAGE = 5;

    /**
     * 计算「最后一页起始索引」（页对齐的最大 scrollOffset）。
     * 原理：旧逻辑 max = size - PAGE 会导致最后一页与上一页重叠（size=12, PAGE=5 时 max=7 仍可看到上一页 7/8/9 三条）；
     *       改为先算 totalPages = (size-1)/PAGE+1，再取 max = (totalPages-1)*PAGE，使最后一页严格从「页首」开始单独显示。
     */
    private static int pageAlignedMax(int size, int perPage) {
        if (size <= 0) return 0;
        int pages = (size - 1) / perPage + 1;
        return Math.max(0, (pages - 1) * perPage);
    }
    /** 订单页滚动偏移 */
    private int orderScrollOffset = 0;
    /** 提交页滚动偏移 */
    private int submitScrollOffset = 0;
    /** 提交页上次向服务端请求刷新列表的 tick（绝对 gameTime 近似） */
    private int submitLastRequestTick = -1000;

    /** 配置页正在编辑的尺寸（懒初始化=站当前配置；每次修改即时保存到服务端） */
    private com.hzldm.createcargodispatch.cargo.CargoDimensions editDims = null;

    /** 配置页：返回当前编辑尺寸（首次从站读取） */
    private com.hzldm.createcargodispatch.cargo.CargoDimensions editDims() {
        if (editDims == null) {
            CargoStationBlockEntity st = getMenu().getStation();
            editDims = st != null ? st.getConfiguredDimensions()
                    : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT;
        }
        return editDims;
    }

    /** 建立联络线按钮（无公司时禁用） */
    private Button connectBtn;

    /** 接单/提交按钮宽度（与 drawAcceptButton / drawSubmitButton 中 btnWidth=48 严格一致） */
    private static final int ACTION_BTN_W = 48;
    private static final int ACTION_BTN_H = 14;

    /**
     * 在 [leftX, rightX] 区间内绘制文本，宽度超限时省略号紧贴 rightX 左边界绘制，消除"左对齐前缀+末尾省略号"导致的右侧大段空白（视觉"缩进"）。
     */
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
        int lo = 0, hi = text.length(), best = 0;
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

    public CargoGeneratorScreen(CargoGeneratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 220;
        // 240：为警告横幅下推列表（最坏 +14）和底部「建立联络线」按钮/页码之间留出安全间距，
        // 避免提交页缺接收器时页码与底部控件重叠串行
        this.imageHeight = 240;
        this.inventoryLabelX = Integer.MAX_VALUE;
    }

    @Override
    protected void init() {
        super.init();
        // 打开 UI 立即请求刷新一次提交列表（默认在订单 Tab 也后台请求，切到提交页立刻有数据）
        if (getMenu().getStation() != null) {
            submitLastRequestTick = -1000; // 立即允许立刻请求，不冷却
            getMenu().requestRefreshSubmitList();
        }
        // 通用上下滚动按钮（订单和提交页都用）——按页翻
        addRenderableWidget(Button.builder(Component.literal("↑"), b -> {
            if (getMenu().getCurrentTab() == CargoGeneratorMenu.Tab.ORDERS) {
                if (orderScrollOffset > 0) orderScrollOffset = Math.max(0, orderScrollOffset - ENTRIES_PER_PAGE);
            } else {
                if (submitScrollOffset > 0) submitScrollOffset = Math.max(0, submitScrollOffset - ENTRIES_PER_PAGE);
            }
        }).bounds(leftPos + imageWidth - 24, topPos + 8, 16, 16).build());

        addRenderableWidget(Button.builder(Component.literal("↓"), b -> {
            if (getMenu().getCurrentTab() == CargoGeneratorMenu.Tab.ORDERS) {
                int max = pageAlignedMax(getMenu().getOrders().size(), ENTRIES_PER_PAGE);
                if (orderScrollOffset < max) orderScrollOffset = Math.min(max, orderScrollOffset + ENTRIES_PER_PAGE);
            } else {
                int max = pageAlignedMax(ClientCargoCache.getSubmitEntries().size(), ENTRIES_PER_PAGE);
                if (submitScrollOffset < max) submitScrollOffset = Math.min(max, submitScrollOffset + ENTRIES_PER_PAGE);
            }
        }).bounds(leftPos + imageWidth - 24, topPos + imageHeight - 24, 16, 16).build());

        // 建立联络线按钮（必须加入公司才可用，containerTick 持续同步禁用态）
        connectBtn = Button.builder(
                Component.translatable("create_cargo_dispatch.linkage.button"),
                b -> {
                    CargoStationBlockEntity station = getMenu().getStation();
                    if (station != null && ClientCompanyCache.isInCompany()) {
                        var pos = station.getBlockPos();
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new ConnectStationPayload(pos.getX(), pos.getY(), pos.getZ()));
                    }
                }).bounds(leftPos + 8, topPos + imageHeight - 20, 80, 14).build();
        connectBtn.active = ClientCompanyCache.isInCompany();
        // 已连接本站则隐藏连接按钮（LINKAGES 未到时下一 containerTick 自动收敛）
        connectBtn.visible = !isCurrentStationLinked();
        addRenderableWidget(connectBtn);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 订单页滚动边界修正（页对齐：最后一页不满时单独显示，不回拉到 size-PAGE 导致与上一页重叠）
        int orderMax = pageAlignedMax(getMenu().getOrders().size(), ENTRIES_PER_PAGE);
        if (orderScrollOffset > orderMax) orderScrollOffset = orderMax;
        // 提交页滚动边界修正
        int submitMax = pageAlignedMax(ClientCargoCache.getSubmitEntries().size(), ENTRIES_PER_PAGE);
        if (submitScrollOffset > submitMax) submitScrollOffset = submitMax;
        // 无公司禁用连接；已连接本站则隐藏连接按钮
        if (connectBtn != null) {
            connectBtn.active = ClientCompanyCache.isInCompany();
            connectBtn.visible = !isCurrentStationLinked();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题 / 类型 / 编号
        CargoStationBlockEntity station = getMenu().getStation();
        StationType stationType = station != null ? station.getStationType() : StationType.GENERIC;
        String stationId = station != null ? station.getStationId() : "";

        graphics.drawCenteredString(font, this.title, leftPos + imageWidth / 2, topPos + 6, TEXT_COLOR);

        int typeColor = getTypeColor(stationType);
        Component typeLabel = Component.translatable("create_cargo_dispatch.label.station_type")
                .append(Component.translatable(stationType.getTranslationKey()));
        graphics.drawCenteredString(font, typeLabel, leftPos + imageWidth / 2, topPos + 18, typeColor);

        Component idLabel;
        if (stationId != null && !stationId.isEmpty()) {
            idLabel = Component.translatable("create_cargo_dispatch.label.station_id")
                    .append(Component.literal("#" + stationId));
        } else {
            idLabel = Component.translatable("create_cargo_dispatch.label.station_id")
                    .append(Component.translatable("create_cargo_dispatch.label.station_id.unknown"));
        }
        graphics.drawCenteredString(font, idLabel, leftPos + imageWidth / 2, topPos + 28, 0xFFCCCCCC);

        // Tab 按钮（y=38）
        renderTabs(graphics, mouseX, mouseY);

        // 分割线（y=50 前一条）
        graphics.fill(leftPos + 8, topPos + 50, leftPos + imageWidth - 24, topPos + 51, 0xFF666666);

        java.util.List<Component> pendingTooltip;
        switch (getMenu().getCurrentTab()) {
            case ORDERS -> pendingTooltip = renderOrdersTab(graphics, mouseX, mouseY);
            case CONFIG -> { renderConfigTab(graphics, mouseX, mouseY); pendingTooltip = null; }
            default -> pendingTooltip = renderSubmitTab(graphics, mouseX, mouseY);
        }

        // 信息分层：画面只显示关键字段（站点/物品/剩余时间），坐标、订单ID、奖励等细节
        // 放到悬停 Tooltip 里查看，避免行内数字过长造成重叠/截断
        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            graphics.renderTooltip(font, pendingTooltip, java.util.Optional.empty(), mouseX, mouseY);
        }

    }

    // =========================================================================
    // Tab 渲染与点击
    // =========================================================================

    private static final int TAB_W = 64;
    private static final int TAB_H = 12;
    private static final int TAB_GAP = 4;

    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int tabY = topPos + 36;
        int totalW = 3 * TAB_W + 2 * TAB_GAP;
        int startX = leftPos + (imageWidth - totalW) / 2;
        CargoGeneratorMenu.Tab cur = getMenu().getCurrentTab();
        CargoGeneratorMenu.Tab[] tabs = CargoGeneratorMenu.Tab.values();
        Component[] labels = {
                Component.translatable("create_cargo_dispatch.tab.orders"),
                Component.translatable("create_cargo_dispatch.tab.submit"),
                Component.translatable("create_cargo_dispatch.tab.config")
        };
        for (int i = 0; i < tabs.length; i++) {
            int x = startX + i * (TAB_W + TAB_GAP);
            int color = tabs[i] == cur ? TAB_COLOR_SELECTED : TAB_COLOR_UNSELECTED;
            drawTabButton(graphics, x, tabY, TAB_W, TAB_H, color, labels[i], mouseX, mouseY);
        }
    }

    /** Tab 起始 X（点击判定与渲染共用，保证二者一致） */
    private int tabStartX() {
        int totalW = 3 * TAB_W + 2 * TAB_GAP;
        return leftPos + (imageWidth - totalW) / 2;
    }

    private void drawTabButton(GuiGraphics g, int x, int y, int w, int h, int color,
                               Component label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? color | 0xFF202020 : color);
        g.renderOutline(x, y, w, h, BORDER_COLOR);
        g.drawCenteredString(font, label, x + w / 2, y + 2, TEXT_COLOR);
    }

    // =========================================================================
    // 配置 Tab：预设 + 三轴微调
    // =========================================================================

    /** 预设按钮几何 {x,y,w,h}（渲染/点击共用） */
    private int[] presetGeom(int i) {
        int w = 96, h = 16, gap = 8;
        int x = leftPos + (imageWidth - (2 * w + gap)) / 2 + i * (w + gap);
        return new int[]{x, topPos + 70, w, h};
    }

    /** 步进按钮几何（axis 0宽/1高/2长；side 0减/1加）{x,y,w,h} */
    private int[] stepperGeom(int axis, int side) {
        int size = 14;
        int rowY = topPos + 108 + axis * 20;
        int plusX = leftPos + imageWidth - 24 - size;
        int minusX = plusX - size - 6;
        return new int[]{side == 0 ? minusX : plusX, rowY, size, size};
    }

    private void renderConfigTab(GuiGraphics g, int mouseX, int mouseY) {
        var dims = editDims();

        // 预设区
        g.drawString(font, Component.translatable("create_cargo_dispatch.config.presets"),
                leftPos + 12, topPos + 58, 0xFFCCCCCC);
        CargoDimensions[] presets = {CargoDimensions.SMALL, CargoDimensions.LARGE};
        for (int i = 0; i < presets.length; i++) {
            int[] r = presetGeom(i);
            boolean selected = dims.equals(presets[i]);
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2]
                    && mouseY >= r[1] && mouseY < r[1] + r[3];
            int col = selected ? 0xFF2D6B2D : (hover ? 0xFF40507A : 0xFF3A3A3A);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], col);
            g.renderOutline(r[0], r[1], r[2], r[3], selected ? 0xFF4A9D4A : BORDER_COLOR);
            g.drawCenteredString(font, presets[i].toString(),
                    r[0] + r[2] / 2, r[1] + 4, TEXT_COLOR);
        }

        // 自定义区
        g.drawString(font, Component.translatable("create_cargo_dispatch.config.custom"),
                leftPos + 12, topPos + 96, 0xFFCCCCCC);
        int[] vals = {dims.width(), dims.height(), dims.length()};
        Component[] names = {
                Component.translatable("create_cargo_dispatch.config.axis_w"),
                Component.translatable("create_cargo_dispatch.config.axis_h"),
                Component.translatable("create_cargo_dispatch.config.axis_l")
        };
        for (int a = 0; a < 3; a++) {
            int rowY = topPos + 108 + a * 20;
            g.drawString(font, names[a], leftPos + 12, rowY + 3, TEXT_COLOR);
            g.drawString(font, Component.literal("§e" + vals[a]),
                    leftPos + 52, rowY + 3, TEXT_COLOR);
            for (int s = 0; s < 2; s++) {
                int[] r = stepperGeom(a, s);
                boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2]
                        && mouseY >= r[1] && mouseY < r[1] + r[3];
                g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xFF4A9D4A : 0xFF2D6B2D);
                g.renderOutline(r[0], r[1], r[2], r[3], BORDER_COLOR);
                g.drawCenteredString(font, s == 0 ? "−" : "+",
                        r[0] + r[2] / 2, r[1] + 2, TEXT_COLOR);
            }
        }

        // 汇总：方块总数 / 容量 / 价格系数
        int capacity = com.hzldm.createcargodispatch.cargo.CargoBalance.defaultItems(
                getMenu().getStation() != null ? getMenu().getStation().getStationType()
                        : com.hzldm.createcargodispatch.cargo.StationType.GENERIC, dims);
        double priceF = com.hzldm.createcargodispatch.cargo.CargoBalance.sizePriceFactor(dims);
        Component summary = Component.translatable("create_cargo_dispatch.config.summary",
                dims.blockCount(), capacity, String.format(java.util.Locale.ROOT, "%.1f", priceF));
        g.drawString(font, summary, leftPos + 12, topPos + 176, 0xFFB0FFB0);
        g.drawString(font, Component.translatable("create_cargo_dispatch.config.hint"),
                leftPos + 12, topPos + 190, 0xFF888888);
    }

    /** 处理配置页点击：预设（2）+ 步进（3×2） */
    private boolean handleConfigClick(double mouseX, double mouseY) {
        // 预设
        CargoDimensions[] presets = {CargoDimensions.SMALL, CargoDimensions.LARGE};
        for (int i = 0; i < presets.length; i++) {
            int[] r = presetGeom(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                editDims = presets[i];
                getMenu().setCargoDimensions(editDims);
                return true;
            }
        }
        // 步进：按当前轴值 ±2（record 的 adjust 已做范围/奇数夹取）
        for (int a = 0; a < 3; a++) {
            for (int s = 0; s < 2; s++) {
                int[] r = stepperGeom(a, s);
                if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                    int delta = s == 0 ? -2 : 2;
                    editDims = switch (a) {
                        case 0 -> editDims().adjustWidth(delta);
                        case 1 -> editDims().adjustHeight(delta);
                        default -> editDims().adjustLength(delta);
                    };
                    getMenu().setCargoDimensions(editDims);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先检测 Tab 点击（3 个，位置与渲染共用 tabStartX）
        int tabY = topPos + 36;
        int startX = tabStartX();
        CargoGeneratorMenu.Tab[] tabs = CargoGeneratorMenu.Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int x = startX + i * (TAB_W + TAB_GAP);
            if (mouseX >= x && mouseX < x + TAB_W
                    && mouseY >= tabY && mouseY < tabY + TAB_H) {
                getMenu().switchTab(tabs[i]);
                return true;
            }
        }

        // 配置页点击（预设 / 三轴步进）
        if (getMenu().getCurrentTab() == CargoGeneratorMenu.Tab.CONFIG) {
            if (handleConfigClick(mouseX, mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Tab=SUBMIT 下的点击（自动提交开关 / 刷新按钮 / 提交按钮）
        if (getMenu().getCurrentTab() == CargoGeneratorMenu.Tab.SUBMIT) {
            // 自动提交开关（左侧 y=52 高度 14 宽度约 140）
            int swX = leftPos + 8;
            int swY = topPos + 52;
            int swW = 148;
            int swH = 14;
            if (mouseX >= swX && mouseX < swX + swW && mouseY >= swY && mouseY < swY + swH) {
                // 取反
                boolean next = !ClientCargoCache.isCurrentAutoSubmit();
                getMenu().toggleAutoSubmit(next);
                return true;
            }
            // 刷新按钮
            int refX = swX + swW + 4;
            int refY = swY;
            int refW = imageWidth - 32 - swW - 4;
            int refH = swH;
            if (mouseX >= refX && mouseX < refX + refW && mouseY >= refY && mouseY < refY + refH) {
                getMenu().requestRefreshSubmitList();
                submitLastRequestTick = -1000; // 立即刷新后再冷却一会儿
                return true;
            }
            // 提交按钮（每条）——未连接检测器时全部禁用
            List<SyncSubmitListPayload.SubmitEntry> entries = ClientCargoCache.getSubmitEntries();
            int rowY = submitListTopY();
            int rowH = 24;
            int rowW = imageWidth - 32;
            int subBtnX = leftPos + 8 + rowW - ACTION_BTN_W - 2;
            if (detLinked()) {
                for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
                    int idx = submitScrollOffset + i;
                    if (idx >= entries.size()) break;
                    int btnY = rowY + 4;
                    if (mouseX >= subBtnX && mouseX < subBtnX + ACTION_BTN_W
                            && mouseY >= btnY && mouseY < btnY + ACTION_BTN_H) {
                        getMenu().submitCargo(entries.get(idx));
                        return true;
                    }
                    rowY += rowH;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Tab=ORDERS：接单按钮点击——未连接生成器时全部禁用
        if (!genLinked()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        List<SyncOrdersPayload.OrderEntry> orders = getMenu().getOrders();
        int rowY = ordersListTopY() + 4;
        int rowHeight = 24;
        int rowWidth = imageWidth - 32;
        int acceptX = leftPos + 8 + rowWidth - ACTION_BTN_W - 2;
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            int orderIdx = orderScrollOffset + i;
            if (orderIdx >= orders.size()) break;
            if (mouseX >= acceptX && mouseX < acceptX + ACTION_BTN_W
                    && mouseY >= rowY && mouseY < rowY + ACTION_BTN_H) {
                getMenu().acceptOrder(orderIdx);
                return true;
            }
            rowY += rowHeight;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // =========================================================================
    // 订单 Tab
    // =========================================================================

    private java.util.List<Component> renderOrdersTab(GuiGraphics graphics, int mouseX, int mouseY) {
        List<SyncOrdersPayload.OrderEntry> orders = getMenu().getOrders();
        java.util.List<Component> pendingTooltip = null;
        int listTop = ordersListTopY();
        // 未连接生成器：红色横幅提示，此时接单按钮全部禁用
        if (!genLinked()) {
            drawWarningBanner(graphics, topPos + 52,
                    Component.translatable("create_cargo_dispatch.station.warn.no_generator"));
        }
        if (orders.isEmpty()) {
            int cx = leftPos + imageWidth / 2;
            int cy = topPos + imageHeight / 2;
            // 空状态优先级：① 未加入公司（公司优先于连接）② 已加入但未连接该站 ③ 已连接但暂无订单
            if (!ClientCompanyCache.isInCompany()) {
                var lines = font.split(
                        Component.translatable("create_cargo_dispatch.company.required"), imageWidth - 24);
                int lineY = cy - (lines.size() * 10) / 2;
                for (var line : lines) {
                    graphics.drawCenteredString(font, line, cx, lineY, 0xFFFF7070);
                    lineY += 10;
                }
            } else if (getMenu().getStation() != null
                    && !ClientCargoCache.hasLinkageAt(getMenu().getStation().getBlockPos(),
                    getMenu().getStation().getStationType().getId())) {
                // 未连接此站结构（含站本体↔检测器坐标兜底）：引导建立联络线
                graphics.drawCenteredString(font,
                        Component.translatable("create_cargo_dispatch.order.empty_not_linked.line1"),
                        cx, cy - 10, 0xFFFFD080);
                graphics.drawCenteredString(font,
                        Component.translatable("create_cargo_dispatch.order.empty_not_linked.line2"),
                        cx, cy + 2, 0xFFFFD080);
            } else {
                // 已连接该站，仅暂无订单（等待刷新）
                graphics.drawCenteredString(font,
                        Component.translatable("create_cargo_dispatch.order.empty"),
                        cx, cy, 0xFFAAAAAA);
            }
            // 空列表也画「共 0 条 · 第 1/1 页」，避免用户以为 GUI 没刷新
            drawPaginationHint(graphics, listTop + ENTRIES_PER_PAGE * 24 + 4,
                    Component.translatable("create_cargo_dispatch.label.count_orders", 0),
                    Component.translatable("create_cargo_dispatch.label.count_pages", 1, 1));
            return pendingTooltip;
        }
        int rowY = listTop;
        int rowHeight = 24;
        int rowWidth = imageWidth - 32;
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            int orderIdx = orderScrollOffset + i;
            if (orderIdx >= orders.size()) break;
            SyncOrdersPayload.OrderEntry order = orders.get(orderIdx);
            int rowX = leftPos + 8;
            int rowColor = (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD;
            graphics.fill(rowX, rowY, rowX + rowWidth, rowY + rowHeight - 2, rowColor);

            // ===== 1) 接单按钮（最右固定，先算好位置给左边留宽） =====
            int acceptX = rowX + rowWidth - ACTION_BTN_W - 2;
            int btnY = rowY + 4;

            // ===== 2) 第一行（y=rowY+4）：#前8位订单号 物品×数量（去掉奖励数字，奖励放 Tooltip） =====
            String header = String.format("#%s  %s×%d",
                    order.orderId().substring(0, Math.min(8, order.orderId().length())),
                    getItemName(order.cargoItemId()),
                    order.cargoCount());
            drawTextEllipsized(graphics, header, rowX + 4, acceptX - 4, rowY + 4, TEXT_COLOR);

            // ===== 3) 第二行（y=rowY+14）：只保留两列：[源→目标] + [⏱mm:ss] =====
            // 坐标信息（源 XYZ/目标 XYZ/完整订单号/奖励）放进 Tooltip，避免画面拥挤
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
            int remainLeftX = acceptX - remainW - 2;
            graphics.drawString(font, Component.literal(remainTxt), remainLeftX, rowY + 14, remainColor);

            String sourceTypeName = getStationTypeShortName(order.sourceStationType());
            String targetTypeName = getStationTypeShortName(order.targetStationType());
            String routeTxt = sourceTypeName + " → " + targetTypeName;
            drawTextEllipsized(graphics, routeTxt, rowX + 4, remainLeftX - 4, rowY + 14, 0xFFA0FFA0);

            // ===== 4) 悬停检测：命中订单信息区（整行除接单按钮区域），生成详细 Tooltip =====
            int infoX1 = rowX, infoX2 = acceptX - 2;
            int infoY1 = rowY, infoY2 = rowY + rowHeight - 2;
            boolean inInfo = mouseX >= infoX1 && mouseX < infoX2 && mouseY >= infoY1 && mouseY < infoY2;
            boolean onBtn = mouseX >= acceptX && mouseX < acceptX + ACTION_BTN_W
                    && mouseY >= btnY && mouseY < btnY + ACTION_BTN_H;
            if (inInfo && !onBtn) {
                java.util.ArrayList<Component> lines = new java.util.ArrayList<>(10);
                lines.add(Component.literal("§6订单 #" + order.orderId()));
                lines.add(Component.literal("§7货物：§f" + getItemName(order.cargoItemId()) + " × " + order.cargoCount()));
                // 订单奖励统一为货运币（完成后入接单玩家所属公司账户）
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

            // ===== 5) 接单按钮（最后画在最上层；未连接生成器时为禁用态） =====
            drawAcceptButton(graphics, acceptX, btnY, mouseX, mouseY, genLinked());
            rowY += rowHeight;
        }
        // ===== 6) 订单列表下方：显示「共 N 条订单 · 第 P / T 页」（有数据才画，空列表走 empty 提示） =====
        int total = orders.size();
        int totalPages = (total <= 0) ? 1 : ((total - 1) / ENTRIES_PER_PAGE + 1);
        int currentPage1 = (orderScrollOffset / ENTRIES_PER_PAGE) + 1;
        if (totalPages < 1) totalPages = 1;
        if (currentPage1 < 1) currentPage1 = 1;
        if (currentPage1 > totalPages) currentPage1 = totalPages;
        drawPaginationHint(graphics, listTop + ENTRIES_PER_PAGE * 24 + 4,
                Component.translatable("create_cargo_dispatch.label.count_orders", total),
                Component.translatable("create_cargo_dispatch.label.count_pages", currentPage1, totalPages));
        return pendingTooltip;
    }

    // =========================================================================
    // 提交 Tab
    // =========================================================================

    /** 提交页每多少 tick 请求一次服务端刷新 → 80 tick ≈ 4 秒（首次打开UI靠主动推送+立即请求，这里是后台兜底刷新） */
    private static final int SUBMIT_REFRESH_INTERVAL = 80;

    private java.util.List<Component> renderSubmitTab(GuiGraphics graphics, int mouseX, int mouseY) {
        // 自动提交开关（y=52），点击切换
        int swX = leftPos + 8;
        int swY = topPos + 52;
        int swW = 148;
        int swH = 14;
        boolean auto = ClientCargoCache.isCurrentAutoSubmit();
        int col = auto ? 0xFF4A9D4A : 0xFF606060;
        graphics.fill(swX, swY, swX + swW, swY + swH, col);
        graphics.renderOutline(swX, swY, swW, swH, BORDER_COLOR);
        Component label = Component.translatable("create_cargo_dispatch.submit.auto." + (auto ? "on" : "off"));
        graphics.drawString(font, label, swX + 4, swY + 3, TEXT_COLOR);

        // 刷新按钮
        int refX = swX + swW + 4;
        int refW = imageWidth - 32 - swW - 4;
        boolean hover = mouseX >= refX && mouseX < refX + refW && mouseY >= swY && mouseY < swY + swH;
        graphics.fill(refX, swY, refX + refW, swY + swH, hover ? 0xFF5060A0 : 0xFF304080);
        graphics.renderOutline(refX, swY, refW, swH, BORDER_COLOR);
        graphics.drawCenteredString(font,
                Component.translatable("create_cargo_dispatch.submit.refresh"),
                refX + refW / 2, swY + 3, TEXT_COLOR);

        // 每 2 秒（40 tick）主动请求一次服务端刷新（实现"实时检测"效果）
        submitLastRequestTick++;
        if (submitLastRequestTick >= SUBMIT_REFRESH_INTERVAL) {
            submitLastRequestTick = 0;
            getMenu().requestRefreshSubmitList();
        }

        // 未连接检测器：列表区顶部红色警告，此时无法送达
        boolean detOk = detLinked();
        int listTopY = submitListTopY();
        if (!detOk) {
            drawWarningBanner(graphics, topPos + 66,
                    Component.translatable("create_cargo_dispatch.station.warn.no_detector"));
        }

        // 货箱列表
        List<SyncSubmitListPayload.SubmitEntry> entries = ClientCargoCache.getSubmitEntries();
        java.util.List<Component> pendingTooltip = null;
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("create_cargo_dispatch.submit.empty"),
                    leftPos + imageWidth / 2,
                    topPos + (detOk ? 110 : 124),
                    0xFFAAAAAA);
            // 空列表也画「共 0 个货箱 · 第 1/1 页」，与订单Tab一致
            drawPaginationHint(graphics, listTopY + ENTRIES_PER_PAGE * 24 + 4,
                    Component.translatable("create_cargo_dispatch.label.count_submit", 0),
                    Component.translatable("create_cargo_dispatch.label.count_pages", 1, 1));
            return pendingTooltip;
        }
        int rowY = listTopY;
        int rowHeight = 24;
        int rowWidth = imageWidth - 32;
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            int idx = submitScrollOffset + i;
            if (idx >= entries.size()) break;
            SyncSubmitListPayload.SubmitEntry entry = entries.get(idx);
            int rowX = leftPos + 8;
            int rowColor = (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD;
            graphics.fill(rowX, rowY, rowX + rowWidth, rowY + rowHeight - 2, rowColor);

            // 渲染前 5 个物品图标（x=rowX+4，y=rowY+4，12×12 缩小版）
            renderEntryItemIcons(graphics, entry, rowX + 4, rowY + 4, 5);

            // 物品总数量文字（y=rowY+4）
            Component countLabel = Component.translatable("create_cargo_dispatch.submit.total", entry.totalItemCount());
            graphics.drawString(font, countLabel, rowX + 72, rowY + 4, 0xFFFFD060);

            // 提交按钮位置（先算好给文字限宽）
            int submitX = rowX + rowWidth - ACTION_BTN_W - 2;
            int btnY = rowY + 4;

            // 来源站类型（y=rowY+14）——用短名（无"货物"后缀），省略号紧贴 submitX-4，消除缩进
            String srcName = getStationTypeShortName(entry.sourceStationType());
            String orderId = !entry.orderId().isEmpty() ? "#" + entry.orderId().substring(0, Math.min(8, entry.orderId().length())) : "";
            String srcRaw = srcName + " " + orderId;
            drawTextEllipsized(graphics, srcRaw, rowX + 72, submitX - 4, rowY + 14, 0xFFA0FFA0);

            // 悬停：来源站名区显示源站完整坐标 + 订单号(完整) + 物品清单摘要
            int srcX1 = rowX + 72, srcX2 = submitX - 4;
            int srcY1 = rowY + 14, srcY2 = rowY + 22;
            boolean onSrcInfo = mouseX >= srcX1 && mouseX < srcX2 && mouseY >= srcY1 && mouseY < srcY2;
            boolean onSubmitBtn = mouseX >= submitX && mouseX < submitX + ACTION_BTN_W
                    && mouseY >= btnY && mouseY < btnY + ACTION_BTN_H;
            if (onSrcInfo && !onSubmitBtn) {
                java.util.ArrayList<Component> lines = new java.util.ArrayList<>(10);
                lines.add(Component.literal("§6" + srcName));
                lines.add(Component.literal("§7坐标：§fX=" + entry.controllerX() + "  Y=" + entry.controllerY() + "  Z=" + entry.controllerZ()));
                if (!entry.orderId().isEmpty()) {
                    lines.add(Component.literal("§7对应订单：#" + entry.orderId()));
                }
                lines.add(Component.literal(""));
                lines.add(Component.literal("§7物品总数：§e" + entry.totalItemCount() + " 件"));
                List<SyncSubmitListPayload.ItemSummary> items = entry.items();
                int showN = Math.min(8, items.size());
                for (int k = 0; k < showN; k++) {
                    SyncSubmitListPayload.ItemSummary it = items.get(k);
                    String name = getItemName(it.itemId());
                    lines.add(Component.literal("  §f- " + name + " × " + it.count()));
                }
                if (items.size() > showN) {
                    lines.add(Component.literal("  §7… 共 " + items.size() + " 种物品"));
                }
                pendingTooltip = lines;
            }

            // 提交按钮（最后画在上层；未连接检测器时为禁用态）
            drawSubmitButton(graphics, submitX, btnY, mouseX, mouseY, detOk);
            rowY += rowHeight;
        }
        // ===== 提交列表下方：显示「共 N 个货箱 · 第 P / T 页」（有数据才画） =====
        int totalSub = entries.size();
        int totalPagesSub = (totalSub <= 0) ? 1 : ((totalSub - 1) / ENTRIES_PER_PAGE + 1);
        int currentPage1Sub = (submitScrollOffset / ENTRIES_PER_PAGE) + 1;
        if (totalPagesSub < 1) totalPagesSub = 1;
        if (currentPage1Sub < 1) currentPage1Sub = 1;
        if (currentPage1Sub > totalPagesSub) currentPage1Sub = totalPagesSub;
        drawPaginationHint(graphics, listTopY + ENTRIES_PER_PAGE * 24 + 4,
                Component.translatable("create_cargo_dispatch.label.count_submit", totalSub),
                Component.translatable("create_cargo_dispatch.label.count_pages", currentPage1Sub, totalPagesSub));
        return pendingTooltip;
    }

    private void renderEntryItemIcons(GuiGraphics g, SyncSubmitListPayload.SubmitEntry entry,
                                      int x, int y, int maxSlots) {
        int slotSize = 12;
        int gap = 1;
        List<SyncSubmitListPayload.ItemSummary> items = entry.items();
        for (int i = 0; i < Math.min(maxSlots, items.size()); i++) {
            SyncSubmitListPayload.ItemSummary it = items.get(i);
            int sx = x + i * (slotSize + gap);
            ItemStack stack = parseItemStack(it.itemId(), it.count());
            // 用 MC 的 renderItem 以 12×12 渲染
            renderItemSmall(g, stack, sx, y, slotSize);
        }
    }

    /** 小尺寸 ItemStack 渲染：先用 renderFakeItem 再叠加文字数量 */
    private void renderItemSmall(GuiGraphics g, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) return;
        // 1. 底层 16×16（MC 不支持任意大小缩放，所以实际绘制 16×16 但按 size 留出位置）
        try {
            g.renderItem(stack, x, y + (size - 16) / 2);
            g.renderItemDecorations(this.font, stack, x, y + (size - 16) / 2, "");
        } catch (Throwable ignore) {
        }
    }

    /** itemId -> ItemStack（仅用于图标渲染） */
    private static final Map<String, ItemStack> ITEM_CACHE = new ConcurrentHashMap<>();
    private ItemStack parseItemStack(String itemId, int count) {
        ItemStack cached = ITEM_CACHE.get(itemId);
        if (cached != null) {
            ItemStack c = cached.copy();
            c.setCount(Math.min(64, Math.max(1, count)));
            return c;
        }
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            ItemStack s = new ItemStack(item);
            ITEM_CACHE.put(itemId, s.copy());
            ItemStack c = s.copy();
            c.setCount(Math.min(64, Math.max(1, count)));
            return c;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // =========================================================================
    // 辅助绘制
    // =========================================================================

    /**
     * 画「共 N 条订单」+「第 P / T 页」双标签（两个 Tab 通用）
     *   - leftLabel：贴 GUI 内边距左侧（x=leftPos+8），颜色浅灰
     *   - rightLabel（页数）：贴 GUI 滚动按钮左侧（x=leftPos+imageWidth-24-2），右对齐，颜色更亮灰
     *   - 自动跳过：entries==0 时调用方已经有 empty 提示，因此传进来的 leftLabel/rightLabel 仍然会被画（避免「空列表 + 空标签空白」难看？
     *     现在默认都画，empty 列表时会显示「共 0 条 · 第 1/1 页」——OK，这能让用户知道当前在第 1 页，而不是 GUI 加载失败）
     */
    private void drawPaginationHint(GuiGraphics g, int y, Component leftLabel, Component rightLabel) {
        int leftX = leftPos + 8;
        int rightX = leftPos + imageWidth - 24 - 2; // 滚动↑↓按钮宽度是 24，贴它左边
        int leftColor = 0xFFBBBBBB;  // 左标签：暗灰
        int rightColor = 0xFFE8E8E8; // 右标签：亮灰（页数更重要，稍微显眼一点）
        g.drawString(font, leftLabel, leftX, y, leftColor, false);
        // 页数右对齐
        int rightW = font.width(rightLabel);
        g.drawString(font, rightLabel, rightX - rightW, y, rightColor, false);
    }
    private int getTypeColor(StationType type) {
        return switch (type) {
            case LUMBER_YARD -> 0xFFA0854A;
            case MINE       -> 0xFF808090;
            case FARM       -> 0xFF70A85A;
            case PASTURE    -> 0xFFD0C0A0;
            case METALLURGY -> 0xFFD4A373;
            case GENERIC    -> 0xFFD0D0D0;
        };
    }

    /** 是否连接生成器：首次状态同步前按已连接处理，避免开屏瞬间误报 */
    private boolean genLinked() {
        return !ClientCargoCache.isLinkStatusReceived() || ClientCargoCache.isCurrentHasGenerator();
    }

    /** 当前打开的货运站是否已建立联络线（连接按钮显隐的唯一判定，已兼容站本体↔检测器坐标/类型） */
    private boolean isCurrentStationLinked() {
        CargoStationBlockEntity st = getMenu().getStation();
        return st != null && ClientCargoCache.hasLinkageAt(
                st.getBlockPos(), st.getStationType().getId());
    }

    /** 是否连接检测器 */
    private boolean detLinked() {
        return !ClientCargoCache.isLinkStatusReceived() || ClientCargoCache.isCurrentHasDetector();
    }

    /** 订单列表起始 Y：无生成器时给红色警告横幅让出位置 */
    private int ordersListTopY() {
        return topPos + (genLinked() ? 52 : 66);
    }

    /** 提交列表起始 Y：无检测器时给红色警告横幅让出位置 */
    private int submitListTopY() {
        return topPos + (detLinked() ? 70 : 84);
    }

    /** 列表区顶部红色警告横幅；文字超宽时整体等比缩小，保证绝不溢出红框 */
    private void drawWarningBanner(GuiGraphics g, int y, Component text) {
        int x1 = leftPos + 8;
        int x2 = leftPos + imageWidth - 24;
        int w = x2 - x1;
        int h = 12;
        g.fill(x1, y, x2, y + h, 0xFF7A2020);
        g.renderOutline(x1, y, w, h, 0xFFC04040);
        int textW = font.width(text);
        int cx = (x1 + x2) / 2;
        if (textW <= w - 8) {
            g.drawCenteredString(font, text, cx, y + 2, 0xFFFFD0D0);
        } else {
            // 等比缩放：以中心点为锚，缩到框宽 -4px 以内
            float scale = Math.max(0.6F, (w - 4) / (float) textW);
            var pose = g.pose();
            pose.pushPose();
            pose.translate(cx, y + 2 + (h - 8 * scale) / 2.0F, 0);
            pose.scale(scale, scale, 1.0F);
            g.drawString(font, text, -Math.round(textW / 2.0F), 0, 0xFFFFD0D0, false);
            pose.popPose();
        }
    }

    private void drawAcceptButton(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, boolean enabled) {
        if (!enabled) {
            graphics.fill(x, y, x + ACTION_BTN_W, y + ACTION_BTN_H, 0xFF3A3A3A);
            graphics.renderOutline(x, y, ACTION_BTN_W, ACTION_BTN_H, 0xFF555555);
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.order.accept"),
                    x + ACTION_BTN_W / 2, y + 3, 0xFF888888);
            return;
        }
        boolean hovered = mouseX >= x && mouseX < x + ACTION_BTN_W && mouseY >= y && mouseY < y + ACTION_BTN_H;
        int color = hovered ? 0xFF4A9D4A : 0xFF2D6B2D;
        graphics.fill(x, y, x + ACTION_BTN_W, y + ACTION_BTN_H, color);
        graphics.renderOutline(x, y, ACTION_BTN_W, ACTION_BTN_H, BORDER_COLOR);
        graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.order.accept"),
                x + ACTION_BTN_W / 2, y + 3, TEXT_COLOR);
    }

    private void drawSubmitButton(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, boolean enabled) {
        if (!enabled) {
            graphics.fill(x, y, x + ACTION_BTN_W, y + ACTION_BTN_H, 0xFF3A3A3A);
            graphics.renderOutline(x, y, ACTION_BTN_W, ACTION_BTN_H, 0xFF555555);
            graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.submit.button"),
                    x + ACTION_BTN_W / 2, y + 3, 0xFF888888);
            return;
        }
        boolean hovered = mouseX >= x && mouseX < x + ACTION_BTN_W && mouseY >= y && mouseY < y + ACTION_BTN_H;
        int color = hovered ? 0xFF4A9D4A : 0xFF2D6B2D;
        graphics.fill(x, y, x + ACTION_BTN_W, y + ACTION_BTN_H, color);
        graphics.renderOutline(x, y, ACTION_BTN_W, ACTION_BTN_H, BORDER_COLOR);
        graphics.drawCenteredString(font, Component.translatable("create_cargo_dispatch.submit.button"),
                x + ACTION_BTN_W / 2, y + 3, TEXT_COLOR);
    }

    private String getItemName(String itemId) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            ItemStack stack = new ItemStack(item);
            return stack.getHoverName().getString();
        } catch (Throwable t) {
            return itemId;
        }
    }

    private String getStationTypeName(String typeId) {
        return Component.translatable("create_cargo_dispatch.station_type." + typeId).getString();
    }

    /** 站点短名（不带"货物"二字），专门用于「起点A→终点B」这类紧凑行，避免过长溢出 */
    private String getStationTypeShortName(String typeId) {
        return Component.translatable("create_cargo_dispatch.station_short." + typeId).getString();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (getMenu().getCurrentTab() == CargoGeneratorMenu.Tab.ORDERS) {
            int max = pageAlignedMax(getMenu().getOrders().size(), ENTRIES_PER_PAGE);
            if (scrollY > 0 && orderScrollOffset > 0) orderScrollOffset = Math.max(0, orderScrollOffset - ENTRIES_PER_PAGE);
            else if (scrollY < 0 && orderScrollOffset < max) orderScrollOffset = Math.min(max, orderScrollOffset + ENTRIES_PER_PAGE);
        } else {
            int max = pageAlignedMax(ClientCargoCache.getSubmitEntries().size(), ENTRIES_PER_PAGE);
            if (scrollY > 0 && submitScrollOffset > 0) submitScrollOffset = Math.max(0, submitScrollOffset - ENTRIES_PER_PAGE);
            else if (scrollY < 0 && submitScrollOffset < max) submitScrollOffset = Math.min(max, submitScrollOffset + ENTRIES_PER_PAGE);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        ClientCargoCache.clearSubmitList();
        super.onClose();
    }
}

