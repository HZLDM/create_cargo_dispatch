package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 货物信息查看助手
 *
 * 原理：向玩家显示货物内容、目标坐标、维度、订单ID
 */
public class CargoInfoHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateCargoDispatch");

    /**
     * 向玩家发送货物信息
     * 原理：合并显示货运信息和 inventory 内的物品清单
     *
     * @param player    玩家
     * @param data      货运数据（订单ID、目标坐标等）
     * @param cargoPos  货箱坐标
     * @param inventory 货箱 inventory（用于显示内部货物）
     */
    public static void sendInfoToPlayer(Player player, CargoData data, BlockPos cargoPos, SimpleContainer inventory) {
        LOGGER.debug("[CargoDispatch] 向玩家 {} 发送货物信息: orderId={}, target={}",
                player.getName().getString(), data.getOrderId(), data.getTargetPos());

        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.header"));
        // 订单ID：空串或 null 时友好显示（直接传 Component，保留玩家侧翻译，不用 getString 破坏本地化）
        String orderId = data.getOrderId();
        if (orderId == null || orderId.isEmpty()) {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.order_id",
                    Component.translatable("create_cargo_dispatch.cargo.info.unbound")));
        } else {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.order_id", orderId));
        }

        // 目标维度
        if (data.getTargetDimension() != null) {
            String dimKey = data.getTargetDimension().toLanguageKey("dimension");
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.target_dim")
                    .append(Component.translatable(dimKey)));
        } else {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.target_dim")
                    .append(Component.translatable("create_cargo_dispatch.cargo.info.none")));
        }

        // 目标坐标：ZERO 视为无目标
        BlockPos tp = data.getTargetPos();
        if (tp == null || tp.equals(BlockPos.ZERO)) {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.target_pos_none"));
        } else {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.target_pos",
                    tp.getX(), tp.getY(), tp.getZ()));
        }

        // 运输类型：null 兜底
        String transportType = data.getTransportType();
        if (transportType == null || transportType.isEmpty()) {
            transportType = "land";
        }
        String typeKey = "create_cargo_dispatch.cargo.transport." + transportType;
        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.transport_type")
                .append(Component.translatable(typeKey)));

        // 显示 inventory 内的物品清单
        if (inventory != null) {
            Map<ItemStack, Integer> contents = summarizeContents(inventory);
            if (contents.isEmpty()) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.contents_empty"));
            } else {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.contents_header"));
                for (Map.Entry<ItemStack, Integer> entry : contents.entrySet()) {
                    ItemStack stack = entry.getKey();
                    int total = entry.getValue();
                    Component itemComp = stack.getHoverName();
                    player.sendSystemMessage(Component.literal(" §7- §f").append(itemComp)
                            .append(" §7x§e" + total));
                }
            }
        }
    }

    /** 统计 inventory 内相同物品的总数 */
    private static Map<ItemStack, Integer> summarizeContents(SimpleContainer inventory) {
        Map<ItemStack, Integer> result = new HashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            // 合并相同物品（按物品类型，忽略 NBT 简化）
            boolean merged = false;
            for (Map.Entry<ItemStack, Integer> entry : result.entrySet()) {
                if (ItemStack.isSameItemSameComponents(entry.getKey(), stack)) {
                    entry.setValue(entry.getValue() + stack.getCount());
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                ItemStack key = stack.copy();
                key.setCount(1);
                result.put(key, stack.getCount());
            }
        }
        return result;
    }
}
