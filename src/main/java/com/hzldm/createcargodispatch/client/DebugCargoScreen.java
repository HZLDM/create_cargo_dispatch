package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.menu.DebugCargoMenu;
import com.hzldm.createcargodispatch.network.ApplyDebugCargoPayload;
import com.hzldm.createcargodispatch.network.SyncDebugCargoPayload;
import com.hzldm.createcargodispatch.network.UpdateDebugCargoDimsPayload;
import com.hzldm.createcargodispatch.network.UpdateDebugCargoFieldsPayload;
import com.hzldm.createcargodispatch.network.UpdateDebugCargoTargetPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.UUID;

/**
 * 调试货箱编辑页（纯配置，无槽位）：
 * 目标站点 ◀▶、订单号（手输/随机）、货物源类型 ◀▶、该类型货物池物品 ◀▶，
 * 最后「生成货箱」把调试物品变为按配置自动填充好的可放置货箱。
 */
public class DebugCargoScreen extends AbstractContainerScreen<DebugCargoMenu> {

    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUB_COLOR = 0xFFA0A0A0;

    private EditBox orderIdBox;

    public DebugCargoScreen(DebugCargoMenu menu, net.minecraft.world.entity.player.Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 152;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos;

        // 目标站 ◀ ▶
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleTarget(-1))
                .bounds(cx + 6, topPos + 14, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleTarget(1))
                .bounds(cx + imageWidth - 20, topPos + 14, 14, 14).build());

        // 订单号输入框 + 随机按钮
        orderIdBox = new EditBox(font, cx + 8, topPos + 33, 104, 13,
                Component.translatable("create_cargo_dispatch.debug_cargo.order_id"));
        orderIdBox.setMaxLength(32);
        orderIdBox.setValue(ClientDebugCargoCache.getOrderId());
        orderIdBox.setHint(Component.translatable("create_cargo_dispatch.debug_cargo.order_id_hint"));
        addRenderableWidget(orderIdBox);
        addRenderableWidget(Button.builder(
                Component.translatable("create_cargo_dispatch.debug_cargo.random"), b -> {
            String id = UUID.randomUUID().toString().substring(0, 8);
            orderIdBox.setValue(id);
            PacketDistributor.sendToServer(
                    UpdateDebugCargoFieldsPayload.orderId(id));
        }).bounds(cx + 116, topPos + 32, 52, 14).build());

        // 源类型 ◀ ▶
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleSourceType(-1))
                .bounds(cx + 6, topPos + 50, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleSourceType(1))
                .bounds(cx + imageWidth - 20, topPos + 50, 14, 14).build());

        // 货物池物品 ◀ ▶
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleCargoItem(-1))
                .bounds(cx + 6, topPos + 70, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleCargoItem(1))
                .bounds(cx + imageWidth - 20, topPos + 70, 14, 14).build());

        // 货箱尺寸 ◀ ▶（标准 3×3×9 / 大型 5×5×15）
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleDims(-1))
                .bounds(cx + 6, topPos + 90, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleDims(1))
                .bounds(cx + imageWidth - 20, topPos + 90, 14, 14).build());

        // 生成货箱
        addRenderableWidget(Button.builder(
                Component.translatable("create_cargo_dispatch.debug_cargo.apply"), b ->
                PacketDistributor.sendToServer(new ApplyDebugCargoPayload()))
                .bounds(cx + 8, topPos + 130, imageWidth - 16, 14).build());
    }

    /** 尺寸预设序列（与配置页预设一致） */
    private static final com.hzldm.createcargodispatch.cargo.CargoDimensions[] DIM_OPTIONS = {
            com.hzldm.createcargodispatch.cargo.CargoDimensions.SMALL,
            com.hzldm.createcargodispatch.cargo.CargoDimensions.LARGE
    };

    private void cycleDims(int delta) {
        com.hzldm.createcargodispatch.cargo.CargoDimensions cur = ClientDebugCargoCache.getDims();
        int idx = 0;
        for (int i = 0; i < DIM_OPTIONS.length; i++) {
            if (DIM_OPTIONS[i].equals(cur)) { idx = i; break; }
        }
        idx = (idx + delta + DIM_OPTIONS.length) % DIM_OPTIONS.length;
        com.hzldm.createcargodispatch.cargo.CargoDimensions next = DIM_OPTIONS[idx];
        ClientDebugCargoCache.setDims(next);
        PacketDistributor.sendToServer(new UpdateDebugCargoDimsPayload(
                next.width(), next.height(), next.length()));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (orderIdBox != null && !orderIdBox.isFocused()) {
            String server = ClientDebugCargoCache.getOrderId();
            if (!server.equals(orderIdBox.getValue())) {
                orderIdBox.setValue(server);
            }
        }
    }

    /** 货物可选集合：所有类型货物池去重联合，可选择任意可能出现的货物 */
    private List<Item> pool() {
        return StationType.getAllCargoPool();
    }

    private int cargoIndex() {
        List<Item> pool = pool();
        String selected = ClientDebugCargoCache.getCargoItemId();
        for (int i = 0; i < pool.size(); i++) {
            if (BuiltInRegistries.ITEM.getKey(pool.get(i)).toString().equals(selected)) return i;
        }
        return 0;
    }

    private void cycleTarget(int delta) {
        List<SyncDebugCargoPayload.TargetEntry> targets = ClientDebugCargoCache.getTargets();
        if (targets.isEmpty()) return;
        int current = ClientDebugCargoCache.getSelectedTarget();
        int next = current < 0
                ? (delta > 0 ? 0 : targets.size() - 1)
                : (current + delta + targets.size()) % targets.size();
        ClientDebugCargoCache.setSelectedTarget(next);
        PacketDistributor.sendToServer(new UpdateDebugCargoTargetPayload(next));
    }

    private void cycleSourceType(int delta) {
        StationType[] types = StationType.values();
        String currentId = ClientDebugCargoCache.getSourceTypeId();
        int idx = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i].getId().equals(currentId)) {
                idx = i;
                break;
            }
        }
        idx = (idx + delta + types.length) % types.length;
        StationType nextType = types[idx];
        // 源类型独立循环，不影响已选择的货物（货物可在全类型联合池中任意选择）
        ClientDebugCargoCache.setSelection(nextType.getId(), null);
        PacketDistributor.sendToServer(
                UpdateDebugCargoFieldsPayload.selection(nextType.getId(), null));
    }

    private void cycleCargoItem(int delta) {
        List<Item> pool = pool();
        int next = (cargoIndex() + delta + pool.size()) % pool.size();
        String itemId = BuiltInRegistries.ITEM.getKey(pool.get(next)).toString();
        ClientDebugCargoCache.setSelection(null, itemId);
        PacketDistributor.sendToServer(UpdateDebugCargoFieldsPayload.cargoItem(itemId));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 输入框聚焦时吞掉普通按键（如 E），避免触发背包键关闭界面；ESC/回车仍正常处理
        if (orderIdBox != null && orderIdBox.isFocused()
                && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_ENTER
                && keyCode != GLFW.GLFW_KEY_KP_ENTER) {
            super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && orderIdBox != null && orderIdBox.isFocused()) {
            PacketDistributor.sendToServer(
                    UpdateDebugCargoFieldsPayload.orderId(orderIdBox.getValue().trim()));
            orderIdBox.setFocused(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BG_COLOR);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font,
                Component.translatable("create_cargo_dispatch.debug_cargo.title"),
                imageWidth / 2, 4, TEXT_COLOR);

        // 目标站
        List<SyncDebugCargoPayload.TargetEntry> targets = ClientDebugCargoCache.getTargets();
        int selected = ClientDebugCargoCache.getSelectedTarget();
        Component targetText;
        int targetColor;
        if (targets.isEmpty()) {
            targetText = Component.translatable("create_cargo_dispatch.debug_cargo.no_target");
            targetColor = 0xFFFF7070;
        } else if (selected < 0 || selected >= targets.size()) {
            targetText = Component.translatable("create_cargo_dispatch.debug_cargo.target_none_selected");
            targetColor = SUB_COLOR;
        } else {
            SyncDebugCargoPayload.TargetEntry t = targets.get(selected);
            targetText = Component.literal(
                    shortTypeName(t.typeId()) + "  (" + t.x() + ", " + t.y() + ", " + t.z() + ")");
            targetColor = 0xFF80FF80;
        }
        List<net.minecraft.util.FormattedCharSequence> targetLines =
                font.split(targetText, imageWidth - 48);
        if (!targetLines.isEmpty()) {
            var line = targetLines.get(0);
            g.drawString(font, line, imageWidth / 2 - font.width(line) / 2, 18, targetColor, false);
        }

        // 源类型
        String sourceId = ClientDebugCargoCache.getSourceTypeId();
        g.drawCenteredString(font,
                Component.translatable(StationType.byId(sourceId).getTranslationKey()),
                imageWidth / 2, 54, 0xFFFFD080);

        // 货物池物品（图标 + 名称）
        List<Item> pool = pool();
        if (!pool.isEmpty()) {
            Item item = pool.get(cargoIndex());
            ItemStack stack = new ItemStack(item);
            g.renderFakeItem(stack, 24, 68);
            Component name = stack.getHoverName();
            List<net.minecraft.util.FormattedCharSequence> nameLines =
                    font.split(name, imageWidth - 70);
            if (!nameLines.isEmpty()) {
                var line = nameLines.get(0);
                g.drawString(font, line, 46, 73, 0xFFFFFF80, false);
            }
        }

        // 货箱尺寸
        var dims = ClientDebugCargoCache.getDims();
        g.drawCenteredString(font,
                Component.translatable("create_cargo_dispatch.config.size_label", dims.toString()),
                imageWidth / 2, 94, 0xFF80C0FF);
    }

    private static String shortTypeName(String typeId) {
        return Component.translatable("create_cargo_dispatch.station_short." + typeId).getString();
    }
}
