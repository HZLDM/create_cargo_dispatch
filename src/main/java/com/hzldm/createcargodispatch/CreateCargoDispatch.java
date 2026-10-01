package com.hzldm.createcargodispatch;

import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.config.ModConfig;
import com.hzldm.createcargodispatch.network.ModPayloads;
import com.hzldm.createcargodispatch.registry.ModBlockEntities;
import com.hzldm.createcargodispatch.registry.ModBlocks;
import com.hzldm.createcargodispatch.registry.ModCreativeTabs;
import com.hzldm.createcargodispatch.registry.ModItems;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import com.hzldm.createcargodispatch.registry.ModSounds;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 机械动力：货运订单 (Create: Cargo Dispatch Order) 模组主入口
 *
 * 原理：
 *  - 通过 @Mod 注解注册模组
 *  - 在构造函数注册 DeferredRegister（方块、物品、BlockEntity、菜单、创造标签、处理器、结构类型、StructurePiece）
 *  - 注册服务端/客户端配置
 *  - 监听 RegisterCapabilitiesEvent 注册货箱的只读 ItemHandler capability
 *  - 监听 RegisterPayloadHandlersEvent 注册网络 payload（订单同步、接单、路径点）
 *  - 客户端侧的 MenuScreen 注册由 ClientSetup 通过 @EventBusSubscriber 处理
 */
@Mod(CreateCargoDispatch.MODID)
public class CreateCargoDispatch {
    public static final String MODID = "create_cargo_dispatch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public CreateCargoDispatch(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModSounds.register(modEventBus);

        // 注册配置
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ModConfig.SERVER_CONFIG);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ModConfig.CLIENT_CONFIG);

        // 注册配置屏幕工厂（仅客户端）
        // 原理：
        //  - IConfigScreenFactory 是客户端类，必须用 FMLEnvironment.dist == Dist.CLIENT 检查
        //    否则服务端加载主类时会触发 ClassNotFoundException
        //  - IConfigScreenFactory 是函数接口，与 Supplier 重载冲突，必须显式 cast 消除歧义
        //  - ConfigurationScreen 会自动根据已注册的 ModConfigSpec 生成配置界面
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerExtensionPoint(
                    net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                    (java.util.function.Supplier<net.neoforged.neoforge.client.gui.IConfigScreenFactory>)
                            () -> (minecraft, parent) ->
                                    new net.neoforged.neoforge.client.gui.ConfigurationScreen(modContainer, parent));
        }

        // 注册 capability
        modEventBus.addListener(CreateCargoDispatch::onRegisterCapabilities);
        // 注册网络 payload（必须，否则菜单打开时发送 SyncOrdersPayload 会抛异常导致 UI 打不开）
        modEventBus.addListener(ModPayloads::register);
        // 联合运输系统网络包独立注册（防止 ModPayloads 继续膨胀）
        modEventBus.addListener(com.hzldm.createcargodispatch.network.CompanyPayloadHandlers::register);
        // 注意：指令注册通过 ModCommands 上的 @EventBusSubscriber 自动注册到 GAME bus
        // RegisterCommandsEvent 不是 IModBusEvent，不能注册到 modEventBus

        // Jade（玉）HUD 软依赖集成：Jade 不存在时内部跳过，无 ClassNotFoundException
        com.hzldm.createcargodispatch.integration.jade.JadeIntegration.init();

        // 注册本模组机器方块的 Create 移动许可（否则连接器等被航空学拖拽装配判为不可移动）
        com.hzldm.createcargodispatch.integration.create.CargoMovementAllowance.init();
    }

    /**
     * 注册货箱方块的只读 ItemHandler capability
     * 原理：所有方向返回 ReadOnlyItemHandler，阻止漏斗等方块提取货物
     */
    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        CargoBlockEntity.registerCapabilities(event);
    }
}
