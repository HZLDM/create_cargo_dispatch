package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.menu.CargoGeneratorMenu;
import com.hzldm.createcargodispatch.menu.CompanyMenu;
import com.hzldm.createcargodispatch.menu.ConnectedStationsMenu;
import com.hzldm.createcargodispatch.menu.PlayerOrdersMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * MenuType 注册类
 * 原理：NeoForge 1.21.1 使用 IMenuTypeExtension.create 创建 MenuType，
 *      支持通过 RegistryFriendlyByteBuf 传递额外同步数据（如 BlockPos）
 */
public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CreateCargoDispatch.MODID);

    /** 货物生成器接单 UI */
    public static final Supplier<MenuType<CargoGeneratorMenu>> CARGO_GENERATOR =
            MENUS.register("cargo_generator",
                    () -> IMenuTypeExtension.create((windowId, inv, data) -> {
                        BlockPos pos = data.readBlockPos();
                        return new CargoGeneratorMenu(windowId, inv, pos);
                    }));

    /** 玩家订单页 UI（无额外数据，直接从 Inventory 构造） */
    public static final Supplier<MenuType<PlayerOrdersMenu>> PLAYER_ORDERS =
            MENUS.register("player_orders",
                    () -> IMenuTypeExtension.create((windowId, inv, data) ->
                            new PlayerOrdersMenu(windowId, inv)));

    /** 已连接站点页 UI（无额外数据，直接从 Inventory 构造） */
    public static final Supplier<MenuType<ConnectedStationsMenu>> CONNECTED_STATIONS =
            MENUS.register("connected_stations",
                    () -> IMenuTypeExtension.create((windowId, inv, data) ->
                            new ConnectedStationsMenu(windowId, inv)));

    /** 联合运输公司页 UI（无额外数据，直接从 Inventory 构造） */
    public static final Supplier<MenuType<CompanyMenu>> COMPANY =
            MENUS.register("company",
                    () -> IMenuTypeExtension.create((windowId, inv, data) ->
                            new CompanyMenu(windowId, inv)));

    /** 调试货箱编辑页（编辑主手调试物品，无额外打开数据） */
    public static final Supplier<MenuType<com.hzldm.createcargodispatch.menu.DebugCargoMenu>> DEBUG_CARGO =
            MENUS.register("debug_cargo",
                    () -> IMenuTypeExtension.create(
                            (windowId, inv, data) -> new com.hzldm.createcargodispatch.menu.DebugCargoMenu(
                                    windowId, inv, data)));
}
