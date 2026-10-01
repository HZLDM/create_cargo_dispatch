package com.hzldm.createcargodispatch.client.render;

import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.config.ModConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 生成器红色检测范围可视化（Shift+右键开关）。
 *
 * 原理：范围语义与检测器一致（连接器模式下，只有载具进入该范围才生成货箱），
 * 仅颜色改为红色以示区分；绘制统一走 {@link RangeBoxDrawing}。
 */
public class GeneratorRangeRenderer implements BlockEntityRenderer<CargoGeneratorBlockEntity> {

    private static final float RED = 0.95F;
    private static final float RED_DIM = 0.20F;

    public GeneratorRangeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AABB getRenderBoundingBox(CargoGeneratorBlockEntity generator) {
        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        int yOffset = ModConfig.getDetectorRangeYOffset();
        return new AABB(generator.getBlockPos())
                .inflate(rangeXZ + 1.0D, rangeY + 1.0D, rangeXZ + 1.0D)
                .move(0, yOffset, 0);
    }

    @Override
    public boolean shouldRender(CargoGeneratorBlockEntity generator, Vec3 cameraPos) {
        return true;
    }

    @Override
    public void render(CargoGeneratorBlockEntity generator, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!generator.isShowRange()) return;

        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        int yOffset = ModConfig.getDetectorRangeYOffset();
        AABB box = new AABB(-rangeXZ, -rangeY + yOffset, -rangeXZ,
                rangeXZ + 1.0D, rangeY + 1.0D + yOffset, rangeXZ + 1.0D);
        RangeBoxDrawing.draw(poseStack, bufferSource, box, RED, RED_DIM, RED_DIM);
    }
}
