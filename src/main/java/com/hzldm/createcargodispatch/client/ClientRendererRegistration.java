package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.client.render.CargoBlockEntityRenderer;
import com.hzldm.createcargodispatch.client.render.DetectorRangeRenderer;
import com.hzldm.createcargodispatch.client.render.GeneratorRangeRenderer;
import com.hzldm.createcargodispatch.client.render.CargoBoxModel;
import com.hzldm.createcargodispatch.client.render.LogCargoModel;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染注册
 *
 * 原理：
 *  - 在 EntityRenderersEvent.RegisterLayerDefinitions 中注册 LayerDefinition
 *  - 在 EntityRenderersEvent.RegisterRenderers 中注册 BlockEntityRenderer
 *  - 仅客户端加载（Dist.CLIENT）
 *
 * 注：NeoForge 1.21.1 中 Bus.MOD 标记为过时，但 RegisterLayerDefinitions / RegisterRenderers
 *     仍在 MOD bus 上发送，因此仍需使用，用 @SuppressWarnings("removal") 抑制警告
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = "create_cargo_dispatch", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientRendererRegistration {

    /**
     * 注册模型层定义
     * 原理：CargoBoxModel（通用货箱）和 LogCargoModel（伐木场专用）需要客户端启动时创建 LayerDefinition
     */
    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CargoBoxModel.LAYER_LOCATION, CargoBoxModel::createBodyLayer);
        event.registerLayerDefinition(LogCargoModel.LAYER_LOCATION, LogCargoModel::createBodyLayer);
    }

    /**
     * 注册 BlockEntityRenderer
     * 原理：将 CargoBlockEntityRenderer 绑定到 CARGO BlockEntityType
     *       所有 5 种货箱方块（GENERIC + 4 种专属）共用同一个渲染器
     *       但 BlockEntityType 不同，需要为每种注册
     */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // GENERIC 货箱
        event.registerBlockEntityRenderer(ModBlockEntities.CARGO.get(), CargoBlockEntityRenderer::new);
        // 4 种专属货箱
        event.registerBlockEntityRenderer(ModBlockEntities.LUMBER_YARD_CARGO.get(), CargoBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MINE_CARGO.get(), CargoBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FARM_CARGO.get(), CargoBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.PASTURE_CARGO.get(), CargoBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.METALLURGY_CARGO.get(), CargoBlockEntityRenderer::new);

        // 检测器检测范围可视化（Shift+右键开关），通用 + 5 种专属共用渲染器
        event.registerBlockEntityRenderer(ModBlockEntities.CARGO_DETECTOR.get(), DetectorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.LUMBER_YARD_DETECTOR.get(), DetectorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MINE_DETECTOR.get(), DetectorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FARM_DETECTOR.get(), DetectorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.PASTURE_DETECTOR.get(), DetectorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.METALLURGY_DETECTOR.get(), DetectorRangeRenderer::new);

        // 生成器红色检测范围可视化（Shift+右键开关），通用 + 5 种专属共用渲染器
        event.registerBlockEntityRenderer(ModBlockEntities.CARGO_GENERATOR.get(), GeneratorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.LUMBER_YARD_GENERATOR.get(), GeneratorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MINE_GENERATOR.get(), GeneratorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FARM_GENERATOR.get(), GeneratorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.PASTURE_GENERATOR.get(), GeneratorRangeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.METALLURGY_GENERATOR.get(), GeneratorRangeRenderer::new);
    }
}
