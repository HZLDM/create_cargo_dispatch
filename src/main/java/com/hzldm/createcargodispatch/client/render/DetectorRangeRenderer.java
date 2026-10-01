package com.hzldm.createcargodispatch.client.render;

import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.config.ModConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 检测器绿色检测范围可视化（Shift+右键开关）。
 *
 * 原理：
 *  - showRange 持久化在检测器 BE 上并随 BlockEntity 更新包同步
 *  - 盒体绘制（填充+边线）统一走 {@link RangeBoxDrawing}
 *  - 范围尺寸与扫描逻辑一致：X/Z ±rangeXZ、Y 中心随 range_y_offset 上移后 ±rangeY
 */
public class DetectorRangeRenderer implements BlockEntityRenderer<CargoDetectorBlockEntity> {

    private static final float GREEN = 0.20F;
    private static final float GREEN_FULL = 0.95F;

    public DetectorRangeRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** 裁剪盒跟随范围（含 Y 偏移），保证 BE 本体离屏时范围框不被视锥剔除 */
    @Override
    public AABB getRenderBoundingBox(CargoDetectorBlockEntity detector) {
        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        int yOffset = ModConfig.getDetectorRangeYOffset();
        return new AABB(detector.getBlockPos())
                .inflate(rangeXZ + 1.0D, rangeY + 1.0D, rangeXZ + 1.0D)
                .move(0, yOffset, 0);
    }

    /** 解除距离剔除：区块已加载即显示 */
    @Override
    public boolean shouldRender(CargoDetectorBlockEntity detector, Vec3 cameraPos) {
        return true;
    }

    @Override
    public void render(CargoDetectorBlockEntity detector, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!detector.isShowRange()) return;

        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        int yOffset = ModConfig.getDetectorRangeYOffset();
        AABB box = new AABB(-rangeXZ, -rangeY + yOffset, -rangeXZ,
                rangeXZ + 1.0D, rangeY + 1.0D + yOffset, rangeXZ + 1.0D);
        RangeBoxDrawing.draw(poseStack, bufferSource, box, GREEN, GREEN_FULL, GREEN);
    }
}
