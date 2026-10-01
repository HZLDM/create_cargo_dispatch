package com.hzldm.createcargodispatch.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * 木材货箱模型（3x3x9）
 *
 * 原理：
 *  - 基于 Blockbench 导出的木材货箱设计
 *  - 包含 cargo（底板+框架）和 woods（9 根原木）两部分
 *  - 仅在主方块位置渲染，通过 BlockEntityRenderer 调用
 *
 * 模型尺寸：48x48x144 像素 = 3x3x9 方块
 *  - 横截面 3x3 在 X-Y 平面
 *  - 长轴 9 格沿 Z 轴延伸
 *  - 9 根原木分 3 层 x 3 列排列
 */
public class LogCargoModel {

    /** 模型层位置，用于客户端注册 LayerDefinition */
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(ResourceLocation.parse("create_cargo_dispatch:log_cargo"), "main");

    private final ModelPart cargo;
    private final ModelPart woods;

    public LogCargoModel(ModelPart root) {
        this.cargo = root.getChild("cargo");
        this.woods = root.getChild("woods");
    }

    /**
     * 创建模型层定义
     * 原理：从 Blockbench 导出的数据，构建 ModelPart 层次结构
     *  - cargo：底板 + 框架 + 角柱
     *  - woods：9 根原木（3 层 x 3 列），每根带 Z 轴旋转
     */
    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // cargo：底板与框架
        PartDefinition cargo = partdefinition.addOrReplaceChild("cargo", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-8.0F, -2.0F, -8.0F, 48.0F, 2.0F, 144.0F, new CubeDeformation(0.0F))
                .texOffs(0, 146).addBox(-8.0F, -2.0F, -8.0F, 48.0F, 2.0F, 144.0F, new CubeDeformation(0.0F))
                .texOffs(62, 641).addBox(-10.0F, -3.0F, -10.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(110, 641).addBox(-10.0F, -49.0F, -10.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(122, 641).addBox(-10.0F, -49.0F, 135.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(134, 641).addBox(39.0F, -49.0F, 135.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(146, 641).addBox(39.0F, -49.0F, -10.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(74, 641).addBox(39.0F, -3.0F, -10.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(86, 641).addBox(39.0F, -3.0F, 135.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(98, 641).addBox(-10.0F, -3.0F, 135.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(0, 641).addBox(-9.0F, -48.0F, 135.0F, 2.0F, 45.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(50, 641).addBox(-9.0F, -14.0F, 86.0F, 1.0F, 12.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 641).addBox(-9.0F, -14.0F, 40.0F, 1.0F, 12.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 641).addBox(40.0F, -14.0F, 40.0F, 1.0F, 12.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(56, 641).addBox(40.0F, -14.0F, 86.0F, 1.0F, 12.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(562, 592).addBox(-9.0F, -46.0F, 40.0F, 1.0F, 30.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(38, 641).addBox(40.0F, -46.0F, 40.0F, 1.0F, 30.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(44, 641).addBox(40.0F, -46.0F, 86.0F, 1.0F, 30.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(32, 641).addBox(-9.0F, -46.0F, 86.0F, 1.0F, 30.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(8, 641).addBox(-9.0F, -48.0F, -9.0F, 2.0F, 45.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(16, 641).addBox(39.0F, -48.0F, -9.0F, 2.0F, 45.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 641).addBox(39.0F, -48.0F, 135.0F, 2.0F, 45.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(288, 592).addBox(-7.0F, -46.0F, 135.0F, 46.0F, 44.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(562, 625).addBox(-7.0F, -16.0F, -8.0F, 46.0F, 14.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(288, 637).addBox(-7.0F, -2.0F, 136.0F, 46.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(476, 640).addBox(-7.0F, -2.0F, -9.0F, 46.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(570, 640).addBox(-7.0F, -16.0F, -9.0F, 46.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(384, 288).addBox(-7.0F, -48.0F, 135.0F, 46.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(382, 638).addBox(-7.0F, -16.0F, 136.0F, 46.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(288, 640).addBox(-7.0F, -32.0F, 136.0F, 46.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 448).addBox(-9.0F, -2.0F, -7.0F, 1.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(572, 288).addBox(40.0F, -2.0F, -7.0F, 1.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(572, 432).addBox(40.0F, -16.0F, -7.0F, 1.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(384, 0).addBox(-9.0F, -48.0F, -7.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(384, 144).addBox(39.0F, -48.0F, -7.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(286, 448).addBox(-9.0F, -16.0F, -7.0F, 1.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(572, 576).addBox(-9.0F, -32.0F, 88.0F, 1.0F, 2.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(192, 592).addBox(40.0F, -32.0F, 88.0F, 1.0F, 2.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(0, 592).addBox(-9.0F, -32.0F, -7.0F, 1.0F, 2.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(96, 592).addBox(40.0F, -32.0F, -7.0F, 1.0F, 2.0F, 47.0F, new CubeDeformation(0.0F))
                .texOffs(382, 592).addBox(-9.0F, -32.0F, 42.0F, 1.0F, 2.0F, 44.0F, new CubeDeformation(0.0F))
                .texOffs(472, 592).addBox(40.0F, -32.0F, 42.0F, 1.0F, 2.0F, 44.0F, new CubeDeformation(0.0F))
                .texOffs(0, 292).addBox(-8.0F, -16.0F, -7.0F, 1.0F, 14.0F, 142.0F, new CubeDeformation(0.0F))
                .texOffs(286, 292).addBox(39.0F, -16.0F, -7.0F, 1.0F, 14.0F, 142.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        // woods：9 根原木（3 层 x 3 列），每根带 Z 轴旋转
        PartDefinition woods = partdefinition.addOrReplaceChild("woods", CubeListBuilder.create(),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        // 顶层（Y=-40）：wood7, wood8, wood9
        addLogWood(woods, "wood7", 32.0F, -40.0F);
        addLogWood(woods, "wood8", 17.0F, -40.0F);
        addLogWood(woods, "wood9", 2.0F, -40.0F);

        // 中层（Y=-26）：wood4, wood5, wood6
        addLogWood(woods, "wood4", 32.0F, -26.0F);
        addLogWood(woods, "wood5", 17.0F, -26.0F);
        addLogWood(woods, "wood6", 2.0F, -26.0F);

        // 底层（Y=-12）：wood, wood2, wood3
        addLogWood(woods, "wood", 17.0F, -12.0F);
        addLogWood(woods, "wood2", 2.0F, -12.0F);
        addLogWood(woods, "wood3", 32.0F, -12.0F);

        return LayerDefinition.create(meshdefinition, 1024, 1024);
    }

    /**
     * 添加一根原木子部件
     * 原理：每根原木尺寸 12x14x140 像素，带 Z 轴 -90° 旋转
     */
    private static void addLogWood(PartDefinition parent, String name, float offsetX, float offsetY) {
        PartDefinition wood = parent.addOrReplaceChild(name, CubeListBuilder.create()
                .texOffs(0, 870).addBox(-7.0F, -4.0F, -6.0F, 12.0F, 14.0F, 140.0F, new CubeDeformation(0.0F)),
                PartPose.offset(offsetX, offsetY, 0.0F));
        // 旋转子 cube（Blockbench 导出的 cube_rN）
        wood.addOrReplaceChild("cube_r" + name.substring(4), CubeListBuilder.create()
                .texOffs(0, 870).addBox(-7.0F, -4.0F, -6.0F, 12.0F, 14.0F, 140.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-4.0F, 2.0F, 0.0F, 0.0F, 0.0F, -1.5708F));
    }

    /**
     * 渲染整个木材货箱模型
     */
    public void render(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        cargo.render(poseStack, vertexConsumer, packedLight, packedOverlay);
        woods.render(poseStack, vertexConsumer, packedLight, packedOverlay);
    }
}
