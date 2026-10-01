package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.GeneratorSpawnMode;
import com.hzldm.createcargodispatch.network.SetGeneratorModePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 货物生成器出货设置界面（普通右键生成器打开）。
 *
 * <p>内容：模式切换按钮 + 当前模式 + 该模式行为说明。
 * 不做虚化 blur（重写 renderBackground，纯色半透明压暗）。
 */
public class GeneratorSettingsScreen extends Screen {

    private static final int WINDOW_W = 200;
    private static final int WINDOW_H = 196;
    private static final int BG_COLOR = 0xFF2B2B2B;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUB_COLOR = 0xFFA0A0A0;
    private static final int GOLD_COLOR = 0xFFFFD760;
    private static final int DIM_COLOR = 0xC0101010;
    private static final int PAD = 16;

    private final BlockPos generatorPos;

    public GeneratorSettingsScreen(BlockPos generatorPos) {
        super(Component.translatable("create_cargo_dispatch.generator.settings_title"));
        this.generatorPos = generatorPos;
    }

    private CargoGeneratorBlockEntity generator() {
        if (minecraft.level.getBlockEntity(generatorPos) instanceof CargoGeneratorBlockEntity g) {
            return g;
        }
        return null;
    }

    private GeneratorSpawnMode currentMode() {
        CargoGeneratorBlockEntity g = generator();
        return g != null ? g.getSpawnMode() : GeneratorSpawnMode.GROUND;
    }

    @Override
    protected void init() {
        int left = (this.width - WINDOW_W) / 2;
        int top = (this.height - WINDOW_H) / 2;

        addRenderableWidget(Button.builder(
                        Component.translatable("create_cargo_dispatch.generator.mode_ground"),
                        b -> sendMode(GeneratorSpawnMode.GROUND))
                .bounds(left + PAD, top + 32, WINDOW_W - PAD * 2, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("create_cargo_dispatch.generator.mode_connector"),
                        b -> sendMode(GeneratorSpawnMode.CONNECTOR))
                .bounds(left + PAD, top + 56, WINDOW_W - PAD * 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                        b -> onClose())
                .bounds(left + PAD, top + WINDOW_H - 32, WINDOW_W - PAD * 2, 20).build());
    }

    private void sendMode(GeneratorSpawnMode mode) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SetGeneratorModePayload(
                generatorPos.getX(), generatorPos.getY(), generatorPos.getZ(), mode.name()));
        onClose();
    }

    /** 屏蔽原版虚化：纯色半透明压暗 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, DIM_COLOR);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        int left = (this.width - WINDOW_W) / 2;
        int top = (this.height - WINDOW_H) / 2;

        g.fill(left, top, left + WINDOW_W, top + WINDOW_H, BG_COLOR);
        g.renderOutline(left, top, WINDOW_W, WINDOW_H, BORDER_COLOR);
        g.drawCenteredString(font, this.title, left + WINDOW_W / 2, top + 12, TEXT_COLOR);

        // 当前模式（金色）
        boolean connector = currentMode() == GeneratorSpawnMode.CONNECTOR;
        Component currentName = Component.translatable(connector
                ? "create_cargo_dispatch.generator.mode_connector"
                : "create_cargo_dispatch.generator.mode_ground");
        g.drawString(font, Component.translatable("create_cargo_dispatch.generator.current", currentName),
                left + PAD, top + 86, GOLD_COLOR);

        // 模式行为说明（自动换行，最多 3 行）
        Component desc = Component.translatable(connector
                ? "create_cargo_dispatch.generator.desc_connector"
                : "create_cargo_dispatch.generator.desc_ground");
        renderWrapped(g, desc, left + PAD, top + 100, WINDOW_W - PAD * 2, SUB_COLOR);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 自动换行绘制（逐字符，行高 11）。
     * 原理：中文句中没有空格，按空格断词会把整句当成一个词导致溢出；
     *       改为逐字符累加，加入下一字符后宽度超限就换行。对 CJK 与英文均适用。
     */
    private void renderWrapped(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        String raw = text.getString();
        StringBuilder line = new StringBuilder();
        int rowY = y;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            String candidate = line.toString() + c;
            // 换行判定：已有内容且加入该字符后超宽；换行符强制换行
            if (line.length() > 0 && (c == '\n' || font.width(candidate) > maxWidth)) {
                g.drawString(font, line.toString(), x, rowY, color);
                rowY += 11;
                line.setLength(0);
                if (c != '\n' && c != ' ') line.append(c);
            } else {
                line.append(c);
            }
        }
        if (line.length() > 0) g.drawString(font, line.toString(), x, rowY, color);
    }
}
