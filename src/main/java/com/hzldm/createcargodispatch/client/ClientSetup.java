package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.registry.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端初始化
 *
 * 原理：
 *  - 在 RegisterMenuScreensEvent 中注册 MenuType → Screen 映射
 *  - 仅客户端执行（Dist.CLIENT）
 *  - RegisterMenuScreensEvent 在 MOD bus 上发送，必须用 Bus.MOD 注册
 *
 * 注：NeoForge 1.21.1 中 Bus.MOD 标记为过时，但仍需使用，
 *     因此用 @SuppressWarnings("removal") 抑制警告
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreateCargoDispatch.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.CARGO_GENERATOR.get(),
                CargoGeneratorScreen::new);
        event.register(ModMenuTypes.PLAYER_ORDERS.get(),
                PlayerOrdersScreen::new);
        event.register(ModMenuTypes.CONNECTED_STATIONS.get(),
                ConnectedStationsScreen::new);
        event.register(ModMenuTypes.COMPANY.get(),
                CompanyScreen::new);
        event.register(ModMenuTypes.DEBUG_CARGO.get(),
                DebugCargoScreen::new);
    }
}
