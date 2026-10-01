package com.hzldm.createcargodispatch.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 检测范围框共享绘制：半透明填充面（不写深度）+ 不透明边线。
 *
 * 原理：填充走 debugStructureQuads（半透明、只写颜色，不遮挡框内模型），
 * 边线走 lines；颜色由调用方指定（检测器绿、生成器红）。
 */
public final class RangeBoxDrawing {

    private static final float FILL_ALPHA = 0.16F;

    private RangeBoxDrawing() {}

    /** 绘制填充面 + 边线（颜色 r/g/b 0~1） */
    public static void draw(PoseStack poseStack, MultiBufferSource bufferSource,
                            AABB box, float r, float g, float b) {
        VertexConsumer fill = bufferSource.getBuffer(RenderType.debugStructureQuads());
        renderFilledBox(poseStack, fill, box, r, g, b, FILL_ALPHA);

        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(
                poseStack, lines, box, r, g, b, 1.0F);
    }

    /** POSITION_COLOR 六面四边形（该渲染层无背面剔除，双面可见） */
    private static void renderFilledBox(PoseStack poseStack, VertexConsumer consumer, AABB box,
                                        float r, float g, float b, float a) {
        Matrix4f m = poseStack.last().pose();
        quad(consumer, m, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, r, g, b, a);
        quad(consumer, m, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
        quad(consumer, m, box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, r, g, b, a);
        quad(consumer, m, box.maxX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, r, g, b, a);
        quad(consumer, m, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.minZ, r, g, b, a);
        quad(consumer, m, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ, r, g, b, a);
    }

    /** 轴对齐四边形（两点某轴相等 → 该轴为面法线） */
    private static void quad(VertexConsumer c, Matrix4f m,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        if (x1 == x2) {
            c.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x1, (float) y1, (float) z2).setColor(r, g, b, a);
            c.addVertex(m, (float) x1, (float) y2, (float) z2).setColor(r, g, b, a);
            c.addVertex(m, (float) x1, (float) y2, (float) z1).setColor(r, g, b, a);
        } else if (y1 == y2) {
            c.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x2, (float) y1, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x2, (float) y1, (float) z2).setColor(r, g, b, a);
            c.addVertex(m, (float) x1, (float) y1, (float) z2).setColor(r, g, b, a);
        } else {
            c.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x2, (float) y1, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x2, (float) y2, (float) z1).setColor(r, g, b, a);
            c.addVertex(m, (float) x1, (float) y2, (float) z1).setColor(r, g, b, a);
        }
    }
}
