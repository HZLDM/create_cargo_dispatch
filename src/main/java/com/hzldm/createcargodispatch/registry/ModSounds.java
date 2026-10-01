package com.hzldm.createcargodispatch.registry;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组声音注册
 *
 * 原理：
 *  - 使用 DeferredRegister 注册自定义 SoundEvent
 *  - sounds.json 定义声音文件映射
 *  - new_order: 新订单生成时播放的提示音
 */
public class ModSounds {

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, CreateCargoDispatch.MODID);

    /** 新订单提示音 */
    public static final DeferredHolder<SoundEvent, SoundEvent> NEW_ORDER =
            SOUNDS.register("new_order", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "new_order")));

    /** 订单超时（未接自动取消）提示音 —— 用户提供的音效文件：临时\提示.ogg，重命名为 sounds/order_timeout.ogg 放入资源包 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ORDER_TIMEOUT =
            SOUNDS.register("order_timeout", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "order_timeout")));

    /** 提交订单成功提示音 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ORDER_ACCEPT =
            SOUNDS.register("order_accept", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "order_accept")));

    /**
     * 订单通用提示音（用户提供的提示.ogg）
     *  用于：1) 玩家点击接单按钮接单成功；2) 订单运抵目标站并成功完成
     *  原理：一个音效涵盖"接单"和"交付完成"两种状态提示，玩家可通过聊天栏文字区分场景
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> ORDER_NOTIFY =
            SOUNDS.register("order_notify", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(CreateCargoDispatch.MODID, "order_notify")));

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
