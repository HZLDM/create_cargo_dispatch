package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 自定义创造模式物品栏
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, CreateCargoDispatch.MODID);

    public static final Supplier<CreativeModeTab> create_cargo_dispatch_TAB = CREATIVE_MODE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_cargo_dispatch"))
                    .icon(() -> new ItemStack(ModItems.CARGO.get()))
                    .displayItems((params, output) -> {
                        // 货箱方块（可放置生成 3x3x9 结构并物理化）
                        output.accept(ModItems.CARGO.get());
                        // 调试货箱（不可放置；右键配置目标/订单/内容后生成可放置货箱）
                        output.accept(ModItems.DEBUG_CARGO.get());
                        // 通用货运方块：潜行+右键选择类型（服务端转换为专属类型物品后放置）
                        output.accept(ModItems.CARGO_STATION.get());
                        output.accept(ModItems.CARGO_GENERATOR.get());
                        output.accept(ModItems.CARGO_DETECTOR.get());
                        // 功能方块：货箱连接器
                        output.accept(ModItems.CARGO_CONNECTOR.get());
                        // 说明：5 种工业类型的专属方块（lumber_yard_*/mine_*/farm_*/pasture_*/metallurgy_*）
                        // 不再单独占用创造栏格子，统一由上面的通用物品通过「潜行+右键 → 选择类型」转换获得；
                        // 专属注册仍然保留（结构生成、类型转换、StationTypeResolver 依赖）。
                    })
                    .build()
    );
}
