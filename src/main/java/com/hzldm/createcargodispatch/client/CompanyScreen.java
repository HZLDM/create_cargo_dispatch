package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.menu.CompanyMenu;
import com.hzldm.createcargodispatch.network.SyncCompanyPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * 联合运输页（背包「联合运输」标签）
 *
 * 原理：
 *  - 无公司态：创建区（名称输入+创建按钮）+ 可加入公司分页列表（行内加入按钮）
 *  - 在司态：成员分页列表（创建者金色标记+行内踢出按钮）+ 邀请输入（创建者）+ 底部解散/退出
 *  - 状态全部来自 ClientCompanyCache（服务端整表推送），切换时本帧自动换视图，提供明确视觉反馈
 *  - 输入框用标准 EditBox 组件；列表行按钮手绘（与 PlayerOrdersScreen 同风格）
 */
public class CompanyScreen extends AbstractContainerScreen<CompanyMenu> {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int ROW_COLOR_EVEN = 0xFF1F1F1F;
    private static final int ROW_COLOR_ODD = 0xFF262626;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUB_COLOR = 0xFFA0A0A0;
    private static final int GOLD_COLOR = 0xFFFFD760;
    private static final int GREEN_COLOR = 0xFF7CFC7C;
    private static final int ROW_X = 8;
    /** 成员/可加入列表行宽（窗口 208，左右各留 8） */
    private static final int ROW_W = 192;
    /** 头像尺寸与头像→名字间距 */
    private static final int FACE_SIZE = 8;
    private static final int FACE_GAP = 2;

    /** 无公司态每页 6 个可加入公司；在司态每页 10/11 个成员 */
    private int joinablePage = 0;
    private int memberPage = 0;

    /** 解散二次确认覆盖层开关（绘制/命中委托 {@link DisbandConfirmOverlay}） */
    private boolean confirmDisband = false;

    private EditBox nameBox;
    private EditBox inviteBox;
    private Button createBtn;
    private Button inviteBtn;
    private Button pageUpBtn;
    private Button pageDownBtn;

    public CompanyScreen(CompanyMenu menu, net.minecraft.world.entity.player.Inventory inv, Component title) {
        super(menu, inv, title);
        // 加宽到 208：为「公司资质 / 公司声望」双卡片提供并排空间，高度保持 208
        this.imageWidth = 208;
        this.imageHeight = 208;
        // 默认标题与自绘标题会重叠（截图蓝框问题）：把原版标题/背包标签全部移出屏幕，完全自绘
        this.titleLabelX = Integer.MAX_VALUE;
        this.titleLabelY = Integer.MAX_VALUE;
        this.inventoryLabelX = Integer.MAX_VALUE;
        this.inventoryLabelY = Integer.MAX_VALUE;
    }

    // =====================================================
    // 初始化 / tick
    // =====================================================

    @Override
    protected void init() {
        super.init();
        nameBox = new EditBox(font, leftPos + 8, topPos + 34, 104, 12,
                Component.translatable("create_cargo_dispatch.company.name_placeholder"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("create_cargo_dispatch.company.name_placeholder"));
        addRenderableWidget(nameBox);

        inviteBox = new EditBox(font, leftPos + 8, topPos + 76, 128, 12,
                Component.translatable("create_cargo_dispatch.company.invite_placeholder"));
        inviteBox.setMaxLength(32);
        inviteBox.setHint(Component.translatable("create_cargo_dispatch.company.invite_placeholder"));
        addRenderableWidget(inviteBox);

        createBtn = Button.builder(Component.translatable("create_cargo_dispatch.company.create"),
                        b -> submitCreate())
                .bounds(leftPos + 116, topPos + 32, 52, 14).build();
        addRenderableWidget(createBtn);

        inviteBtn = Button.builder(Component.translatable("create_cargo_dispatch.company.invite"),
                        b -> submitInvite())
                .bounds(leftPos + 144, topPos + 75, 56, 14).build();
        addRenderableWidget(inviteBtn);

        pageUpBtn = Button.builder(Component.literal("↑"), b -> pageBack())
                .bounds(leftPos + 136, topPos + 5, 12, 11).build();
        addRenderableWidget(pageUpBtn);
        pageDownBtn = Button.builder(Component.literal("↓"), b -> pageForward())
                .bounds(leftPos + 150, topPos + 5, 12, 11).build();
        addRenderableWidget(pageDownBtn);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean in = getMenu().isInCompany();
        boolean creator = getMenu().isCreator();
        // 同步包到达导致离开公司/失去创建者身份时（如被踢/解散），自动关闭确认态
        if (confirmDisband && (!in || !creator)) confirmDisband = false;
        // modal=解散确认弹窗打开：背景所有 Widget 必须隐藏，
        // 否则它们在弹窗之后渲染会穿透压到面板之上（按钮层错误/文字叠加的根因）
        boolean modal = confirmDisband;
        nameBox.visible = !in && !modal;
        createBtn.visible = !in && !modal;
        inviteBox.visible = in && creator && !modal;
        inviteBtn.visible = in && creator && !modal;
        if (!nameBox.visible && getFocused() == nameBox) setFocused(null);
        if (!inviteBox.visible && getFocused() == inviteBox) setFocused(null);
        int max = pageMax();
        int current = in ? memberPage : joinablePage;
        if (current > max) {
            if (in) memberPage = max;
            else joinablePage = max;
        }
        pageUpBtn.visible = current > 0 && !modal;
        pageDownBtn.visible = current < max && !modal;
    }

    // =====================================================
    // 渲染
    // =====================================================

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 标题：在司用绿色公司名给出明确的状态切换视觉提示；限制宽度避免与翻页按钮重叠
        Component title = getMenu().isInCompany()
                ? Component.literal(font.plainSubstrByWidth(getMenu().getCompanyName(), 124))
                : Component.translatable("create_cargo_dispatch.company.title");
        g.drawString(font, title, leftPos + 8, topPos + 7,
                getMenu().isInCompany() ? GREEN_COLOR : TEXT_COLOR);
        // 分割线
        g.fill(leftPos + 8, topPos + 18, leftPos + imageWidth - 8, topPos + 19, 0xFF666666);

        if (getMenu().isInCompany()) {
            CompanyProgressionPanel.render(g, font, leftPos, topPos, getMenu(), getMenu().isCreator(),
                    mouseX, mouseY);
            renderCompanyView(g, mouseX, mouseY);
        } else {
            renderJoinView(g, mouseX, mouseY);
        }

        // 解散二次确认覆盖页最后绘制，压在所有内容之上
        if (confirmDisband) {
            DisbandConfirmOverlay.render(g, font, leftPos, topPos, imageWidth,
                    this.width, this.height, mouseX, mouseY);
        }
    }

    /** 获取玩家皮肤（在线用 PlayerInfo，离线/未加载用默认皮肤） */
    private PlayerSkin getSkin(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(playerId);
            if (info != null) return info.getSkin();
        }
        return DefaultPlayerSkin.get(playerId);
    }

    /** 无公司态：创建区 + 可加入列表 */
    private void renderJoinView(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.create_section"),
                leftPos + 8, topPos + 24, SUB_COLOR, false);

        int listY0 = topPos + 70;
        int rowH = 20;
        List<SyncCompanyPayload.JoinableEntry> list = getMenu().getJoinables();
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.joinable_section", list.size()),
                leftPos + 8, topPos + 58, SUB_COLOR, false);
        drawPageIndicator(g, topPos + 58, joinablePage);

        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("create_cargo_dispatch.company.empty_joinable"),
                    leftPos + imageWidth / 2, topPos + imageHeight / 2, SUB_COLOR);
            return;
        }
        int start = joinablePage * 6;
        int end = Math.min(list.size(), start + 6);
        for (int i = start; i < end; i++) {
            int rowY = listY0 + (i - start) * rowH;
            g.fill(leftPos + ROW_X, rowY, leftPos + ROW_X + ROW_W, rowY + rowH - 2,
                    (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD);
            SyncCompanyPayload.JoinableEntry e = list.get(i);
            g.drawString(font, font.plainSubstrByWidth(e.name(), 148),
                    leftPos + ROW_X + 4, rowY + 2, TEXT_COLOR, false);
            String sub = Component.translatable("create_cargo_dispatch.company.joinable_sub",
                    e.creatorName(), e.memberCount()).getString();
            g.drawString(font, font.plainSubstrByWidth(sub, 148),
                    leftPos + ROW_X + 4, rowY + 11, SUB_COLOR, false);
            drawHandButton(g, mouseX, mouseY, leftPos + ROW_X + ROW_W - 36, rowY + 3, 28, 14,
                    Component.translatable("create_cargo_dispatch.company.join"), false);
        }
    }

    /** 在司态：双卡片（21~71）+ 邀请区（创建者，75~89）+ 成员列表 + 底部操作 */
    private void renderCompanyView(GuiGraphics g, int mouseX, int mouseY) {
        boolean creator = getMenu().isCreator();
        int rowH = 13;
        // 成员列表：创建者邀请区在 75~89，从 98 开始；非创建者从 87 开始
        int listY0 = topPos + (creator ? 98 : 87);
        int perPage = creator ? 6 : 7;
        List<SyncCompanyPayload.MemberEntry> members = getMenu().getMembers();

        // 成员计数（创建者的邀请用途由输入框 placeholder 提示，不再占用额外行）
        int sectionY = topPos + (creator ? 90 : 77);
        g.drawString(font, Component.translatable("create_cargo_dispatch.company.members_section", members.size()),
                leftPos + 8, sectionY, SUB_COLOR, false);
        drawPageIndicator(g, sectionY, memberPage);

        int start = memberPage * perPage;
        int end = Math.min(members.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int rowY = listY0 + (i - start) * rowH;
            g.fill(leftPos + ROW_X, rowY, leftPos + ROW_X + ROW_W, rowY + rowH - 1,
                    (i % 2 == 0) ? ROW_COLOR_EVEN : ROW_COLOR_ODD);
            SyncCompanyPayload.MemberEntry m = members.get(i);
            // 头像（在线取皮肤，离线/未加载取默认皮肤），与名称垂直居中对齐
            PlayerFaceRenderer.draw(g, getSkin(m.id()), leftPos + ROW_X + 4, rowY + 2, FACE_SIZE);
            int nameColor = m.creator() ? GOLD_COLOR : TEXT_COLOR;
            int nameX = leftPos + ROW_X + 4 + FACE_SIZE + FACE_GAP + 2;
            int maxNameW = (leftPos + ROW_X + ROW_W) - nameX - (creator && !m.creator() ? 40 : 6);
            g.drawString(font, font.plainSubstrByWidth(m.name(), maxNameW),
                    nameX, rowY + 3, nameColor, false);
            // 创建者：非创建者成员行尾显示踢出按钮（不能踢创建者/自己）
            if (creator && !m.creator()) {
                drawHandButton(g, mouseX, mouseY, leftPos + ROW_X + ROW_W - 36, rowY + 1, 28, 11,
                        Component.translatable("create_cargo_dispatch.company.kick"), true);
            }
        }

        // 底部操作：创建者=解散（红），成员=退出
        int actionY = topPos + imageHeight - 18;
        boolean disband = creator;
        drawHandButton(g, mouseX, mouseY, leftPos + 8, actionY, ROW_W, 13,
                Component.translatable(disband
                        ? "create_cargo_dispatch.company.disband"
                        : "create_cargo_dispatch.company.leave"), true);
    }

    /** 手绘列表/底部按钮（fill+outline+居中文字），返回命中信息由 mouseClicked 复算 */
    private void drawHandButton(GuiGraphics g, int mouseX, int mouseY,
                                int x, int y, int w, int h, Component label, boolean danger) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int color;
        if (danger) color = hover ? 0xFF8B3A3A : 0xFF5A2A2A;
        else color = hover ? 0xFF3A8B4E : 0xFF2A5A34;
        g.fill(x, y, x + w, y + h, color);
        g.renderOutline(x, y, w, h, BORDER_COLOR);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2 + 1, TEXT_COLOR);
    }

    // =====================================================
    // 交互
    // =====================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 确认覆盖层显示时，所有点击由覆盖层独占（防止穿透下层）
        if (confirmDisband) {
            DisbandConfirmOverlay.ClickResult r =
                    DisbandConfirmOverlay.click(leftPos, topPos, imageWidth, mouseX, mouseY);
            if (r == DisbandConfirmOverlay.ClickResult.CONFIRM) {
                confirmDisband = false;
                getMenu().leaveCompany(); // 创建者=解散，服务端权威判定
            } else if (r == DisbandConfirmOverlay.ClickResult.DISMISS) {
                confirmDisband = false;
            }
            return true;
        }
        // 先处理手绘按钮（与组件不重叠），未命中再交给 EditBox/翻页组件
        if (getMenu().isInCompany()) {
            if (CompanyProgressionPanel.mouseClicked(getMenu(), mouseX, mouseY, leftPos, topPos,
                    getMenu().isCreator())) return true;
            if (handleMemberClick(mouseX, mouseY)) return true;
        } else if (handleJoinClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleJoinClick(double mouseX, double mouseY) {
        int listY0 = topPos + 70;
        int rowH = 20;
        List<SyncCompanyPayload.JoinableEntry> list = getMenu().getJoinables();
        int start = joinablePage * 6;
        int end = Math.min(list.size(), start + 6);
        for (int i = start; i < end; i++) {
            int rowY = listY0 + (i - start) * rowH;
            int bx = leftPos + ROW_X + ROW_W - 36;
            if (mouseX >= bx && mouseX < bx + 28 && mouseY >= rowY + 3 && mouseY < rowY + 17) {
                getMenu().joinCompany(list.get(i).id());
                return true;
            }
        }
        return false;
    }

    private boolean handleMemberClick(double mouseX, double mouseY) {
        boolean creator = getMenu().isCreator();
        int rowH = 13;
        int listY0 = topPos + (creator ? 98 : 87);
        int perPage = creator ? 6 : 7;
        List<SyncCompanyPayload.MemberEntry> members = getMenu().getMembers();
        int start = memberPage * perPage;
        int end = Math.min(members.size(), start + perPage);
        if (creator) {
            for (int i = start; i < end; i++) {
                SyncCompanyPayload.MemberEntry m = members.get(i);
                if (m.creator()) continue;
                int rowY = listY0 + (i - start) * rowH;
                int bx = leftPos + ROW_X + ROW_W - 36;
                if (mouseX >= bx && mouseX < bx + 28 && mouseY >= rowY + 1 && mouseY < rowY + 12) {
                    getMenu().kickMember(m.id());
                    return true;
                }
            }
        }
        // 底部操作按钮：创建者进入二次确认覆盖页；普通成员直接退出
        int actionY = topPos + imageHeight - 18;
        if (mouseX >= leftPos + 8 && mouseX < leftPos + 8 + ROW_W
                && mouseY >= actionY && mouseY < actionY + 13) {
            if (creator) confirmDisband = true;
            else getMenu().leaveCompany();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 确认覆盖层显示时吞掉字符输入，焦点 EditBox 不会被误输入
        if (confirmDisband) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 确认覆盖层：ESC 当作「取消」，其余按键吞掉（不允许直接关闭页面绕过确认）
        if (confirmDisband) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) confirmDisband = false;
            return true;
        }
        // 输入框聚焦时回车直接提交，降低操作成本
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameBox.isFocused()) {
                submitCreate();
                return true;
            }
            if (inviteBox.isFocused()) {
                submitInvite();
                return true;
            }
        }
        // 输入框聚焦时吞掉普通按键（如 E），否则会触发背包键关闭界面；ESC 仍可关闭
        if (keyCode != GLFW.GLFW_KEY_ESCAPE
                && (nameBox.isFocused() || inviteBox.isFocused())) {
            super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (confirmDisband) return true;
        // 仅面板区域内响应滚轮，面板外滚动不再误翻页
        if (mouseX < leftPos || mouseX > leftPos + imageWidth
                || mouseY < topPos || mouseY > topPos + imageHeight) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (getMenu().isInCompany()) {
            int max = pageMax();
            if (scrollY > 0) memberPage = Math.max(0, memberPage - 1);
            else if (scrollY < 0) memberPage = Math.min(max, memberPage + 1);
        } else {
            if (scrollY > 0) joinablePage = Math.max(0, joinablePage - 1);
            else if (scrollY < 0) joinablePage = Math.min(pageMax(), joinablePage + 1);
        }
        return true;
    }

    private void submitCreate() {
        getMenu().createCompany(nameBox.getValue());
        nameBox.setValue("");
    }

    private void submitInvite() {
        getMenu().invite(inviteBox.getValue());
        inviteBox.setValue("");
    }

    private int perPage() {
        return getMenu().isInCompany() ? (getMenu().isCreator() ? 6 : 7) : 6;
    }

    private int listSize() {
        return getMenu().isInCompany() ? getMenu().getMembers().size() : getMenu().getJoinables().size();
    }

    /** 页对齐最大页索引 */
    private int pageMax() {
        int size = listSize();
        int per = perPage();
        if (size <= 0) return 0;
        return (size - 1) / per;
    }

    /** 多页时在标题行右侧绘制「当前页/总页数」（纯数字，无需翻译） */
    private void drawPageIndicator(GuiGraphics g, int y, int page) {
        int max = pageMax();
        if (max <= 0) return;
        String text = (page + 1) + "/" + (max + 1);
        g.drawString(font, text, leftPos + ROW_X + ROW_W - font.width(text), y, SUB_COLOR, false);
    }

    private void pageBack() {
        if (getMenu().isInCompany()) memberPage = Math.max(0, memberPage - 1);
        else joinablePage = Math.max(0, joinablePage - 1);
    }

    private void pageForward() {
        int max = pageMax();
        if (getMenu().isInCompany()) memberPage = Math.min(max, memberPage + 1);
        else joinablePage = Math.min(max, joinablePage + 1);
    }
}
