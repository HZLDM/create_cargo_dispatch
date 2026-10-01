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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 货箱整体模型（3x3x9）
 *
 * 原理：
 *  - 基于 Blockbench 设计的货箱模型手工编写
 *  - 整个货箱作为一个 ModelPart 渲染，覆盖 3x3x9 空间
 *  - 仅在主方块位置渲染一次，其他 26 个方块不渲染
 *  - 通过 BlockEntityRenderer 调用 renderToBuffer
 *
 * 模型尺寸：48x48x144 像素 = 3x3x9 方块
 *  - 横截面 3x3 在 X-Y 平面
 *  - 长轴 9 格沿 Z 轴延伸
 */
public class CargoBoxModel {

    /** 模型层位置，用于客户端注册 LayerDefinition */
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(ResourceLocation.parse("create_cargo_dispatch:cargo_box"), "main");

    private final ModelPart cargoBox;

    public CargoBoxModel(ModelPart root) {
        this.cargoBox = root.getChild("cargo box");
    }

    /**
     * 创建模型层定义
     * 原理：从 Blockbench 导出的数据，构建 ModelPart 层次结构
     */
    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // 主 cube：3x3x9 货箱主体
        PartDefinition cargoBox = partdefinition.addOrReplaceChild("cargo box",
                CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, -48.0F, -8.0F, 48.0F, 48.0F, 144.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 21.0F, 0.0F));

        // 角落装饰
        PartDefinition corner = cargoBox.addOrReplaceChild("corner",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.0F, -3.0F, -1.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(47.0F, -3.0F, -1.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(47.0F, 46.0F, -1.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(-2.0F, 46.0F, -1.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(-2.0F, -3.0F, -146.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(47.0F, -3.0F, -146.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(47.0F, 46.0F, -146.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 0).addBox(-2.0F, 46.0F, -146.0F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-8.0F, -47.0F, 136.0F));

        // 骨架边框
        PartDefinition bone = cargoBox.addOrReplaceChild("bone",
                CubeListBuilder.create()
                        .texOffs(0, 192).addBox(-1.0F, -2.0F, -140.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(47.0F, -2.0F, -140.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(47.0F, 46.0F, -140.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(-1.0F, 46.0F, -140.0F, 2.0F, 2.0F, 142.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(1.0F, 46.0F, -142.0F, 46.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(1.0F, -2.0F, -142.0F, 46.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(1.0F, -2.0F, 2.0F, 46.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 192).addBox(1.0F, 46.0F, 2.0F, 46.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 196).addBox(47.0F, 0.0F, 2.0F, 2.0F, 46.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 196).addBox(-1.0F, 0.0F, 2.0F, 2.0F, 46.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 196).addBox(-1.0F, 0.0F, -142.0F, 2.0F, 46.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 196).addBox(47.0F, 0.0F, -142.0F, 2.0F, 46.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 7).addBox(22.0F, 20.0F, -142.0F, 4.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-8.0F, -47.0F, 133.0F));

        // 旋转子部件
        PartDefinition cube_r1 = bone.addOrReplaceChild("cube_r1",
                CubeListBuilder.create().texOffs(0, 7).addBox(-2.0F, 18.0F, 0.0F, 4.0F, 6.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(24.0F, 2.0F, 4.0F, 0.0F, 3.1416F, 0.0F));

        return LayerDefinition.create(meshdefinition, 512, 512);
    }

    /**
     * 渲染整个货箱模型
     */
    public void render(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        cargoBox.render(poseStack, vertexConsumer, packedLight, packedOverlay);
    }
}
