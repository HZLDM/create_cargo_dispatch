package com.hzldm.createcargodispatch.client.render;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 货箱 BlockEntity 渲染器
 *
 * 原理：
 *  - 根据 StationType 选择对应模型和纹理
 *    - LUMBER_YARD 使用 LogCargoModel + log_cargo.png（带原木的木材货箱）
 *    - 其他类型使用 CargoBoxModel + cargo_box.png（通用货箱）
 *  - 仅在主方块位置渲染整个模型，其他 26 个方块不渲染
 *  - 主方块在结构中心，模型以主方块为中心向两侧延伸
 *
 * 变换顺序：translate(0.5,0,0.5) → scale(1,-1,1) → rotate → translate(tx,ty,tz)
 *  - tx=-1, tz=-4 对所有模型相同（X/Z 居中）
 *  - ty 因模型 Y 范围不同而异：
 *    - CargoBoxModel: ty=-1.3125（Y∈[-1.6875,1.3125] → [0,3]）
 *    - LogCargoModel: ty=0（Y∈[-3.0625,0] → [0,3.0625]）
 */
public class CargoBlockEntityRenderer implements BlockEntityRenderer<CargoBlockEntity> {

    private static final ResourceLocation CARGO_BOX_TEXTURE =
            ResourceLocation.parse("create_cargo_dispatch:textures/block/cargo_box.png");
    private static final ResourceLocation LOG_CARGO_TEXTURE =
            ResourceLocation.parse("create_cargo_dispatch:textures/block/log_cargo.png");

    /** 模型按 3×3×9 基准构建；各轴独立缩放到实际尺寸（尺寸来自货箱 cargoData） */

    private final CargoBoxModel cargoBoxModel;
    private final LogCargoModel logCargoModel;

    public CargoBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.cargoBoxModel = new CargoBoxModel(CargoBoxModel.createBodyLayer().bakeRoot());
        this.logCargoModel = new LogCargoModel(LogCargoModel.createBodyLayer().bakeRoot());
    }

    /**
     * 【关键修复】渲染视锥 AABB：从默认的 1×1×1 扩大到 13×13×13（inflate 6）
     * 根因：
     *  - 货箱是 3×3×9 的大体积模型（长度方向 Z 轴 9 格，旋转后变 X 轴 9 格）
     *  - BlockEntityRenderer 默认的 getRenderBoundingBox 返回以 pos 为中心的 1×1×1 小盒子
     *  - NeoForge 在视锥剔除阶段（调用 shouldRenderOffScreen/shouldRender 之前）会先检查 AABB 是否在视锥内
     *  - 大模型 AABB 设置过小的结果：相机稍微偏离，中心 1x1x1 不在视锥 → 整个大模型被跳过不渲染
     *  - 伐木场 LogCargoModel 尺寸更大（顶部原木会伸出），所以比通用货箱更早出现"消失"症状
     */
    @Override
    public AABB getRenderBoundingBox(CargoBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        // inflate(8) → 总 17×17×17，覆盖放大后货箱最大尺寸 15（长）×5×5 及所有旋转方向
        return new AABB(pos).inflate(8.0D);
    }

    /**
     * 渲染距离：解除默认 64（8格）的限制，改为 128² = 16384（128 格内都渲染）
     * 原因：BER 默认 getMaxRenderDistanceSquared = 64.0D，玩家距离货箱 >8 格就不调用 render()，
     *       货箱的视觉模型完全依赖 BER（不是 baked JSON 模型），所以远距离会"消失"。
     */
    @Override
    public int getViewDistance() {
        return 128;  // NeoForge 新增的 viewDistance 覆盖，优先于平方距离
    }

    /** 兜底：兼容老版本的平方距离判定，和 128 格对应 */
    @Override
    public boolean shouldRender(CargoBlockEntity blockEntity, Vec3 cameraPos) {
        // 永远返回 true：无论距离多远，只要区块被加载就渲染
        // 防止 NeoForge 在 shouldRender 层面做距离剔除导致子世界/远距离货箱不可见
        return true;
    }

    /**
     * 视锥剔除兜底：始终返回 true，允许屏幕外/子世界坐标的方块被渲染
     * 原因：
     *  - Sable 的 SubLevel 会把货箱放在子世界坐标系里，经过坐标变换后视锥剔除判定极易出错（误判为屏幕外）
     *  - 货箱是"大体积模型"，中心在视锥外但边缘可能在屏幕内
     */
    @Override
    public boolean shouldRenderOffScreen(CargoBlockEntity blockEntity) {
        return true;
    }

    @Override
    public void render(CargoBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!blockEntity.isMainBlock()) {
            return;
        }

        StationType stationType = blockEntity.getStationType();
        boolean isLumberYard = (stationType == StationType.LUMBER_YARD);

        BlockState state = blockEntity.getBlockState();
        net.minecraft.core.Direction.Axis axis = state.getValue(CargoBlock.HORIZONTAL_AXIS);

        poseStack.pushPose();

        // 1. 方块西北角 → 底部中心
        poseStack.translate(0.5, 0, 0.5);

        // 1.5 按货箱实际尺寸三轴独立缩放：模型基准为 3(X)×3(Y)×9(Z)。
        //     缩放中心为主方块锚点（截面中心、底部），故对齐不变；两套模型/UV 无需改动。
        CargoDimensions dims = blockEntity.getCargoData() != null
                ? blockEntity.getCargoData().getDimensions() : CargoDimensions.DEFAULT;
        poseStack.scale(dims.width() / 3.0F, dims.height() / 3.0F, dims.length() / 9.0F);

        // 2. Y 翻转（Blockbench Y 向下为正，MC Y 向上为正）
        poseStack.scale(1.0F, -1.0F, 1.0F);

        // 3. 根据 HORIZONTAL_AXIS 旋转（在模型坐标系中旋转，再平移）
        if (axis == net.minecraft.core.Direction.Axis.X) {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
        }

        // 4. X/Y/Z 偏移（居中模式，在旋转后的坐标系中平移）
        //    ty 因模型 Y 范围不同而异：
        //      - CargoBoxModel: ty=-1.3125（原模型 Y∈[-1.6875,1.3125] → [0,3]）
        //      - LogCargoModel: ty=-1.5（模型原点偏低，需上移 1.5 格对齐底部）
        float ty = isLumberYard ? -1.5F : -1.3125F;
        poseStack.translate(-1.0, ty, -4.0);

        ResourceLocation texture = isLumberYard ? LOG_CARGO_TEXTURE : CARGO_BOX_TEXTURE;
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));

        if (isLumberYard) {
            logCargoModel.render(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
        } else {
            cargoBoxModel.render(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);
        }

        poseStack.popPose();
    }
}
