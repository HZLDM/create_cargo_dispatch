package com.hzldm.createcargodispatch.item;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 纯净 BlockItem：阻止 BLOCK_ENTITY_DATA DataComponent 被附加到创造模式 ItemStack
 *
 * 问题背景与原理：
 *  Minecraft 1.20.5+ 引入 DataComponents 系统，BlockItem 会自动将关联 BlockEntity 的
 *  默认 NBT 快照（BLOCK_ENTITY_DATA 组件）附加到 ItemStack。
 *
 *  外部模组（如 Sable、Jade/HWYLA、部分 Waila 附属）会读取这个组件的 raw bytes，
 *  并尝试 "toString()" 后追加到 Item tooltip 显示（例如"质量 N kg""BlockEntityTag 数据"）。
 *
 *  但当 BlockEntity NBT 中存在编码污染（例如 UTF-8 BOM 被当成普通 char 存进字符串字段）
 *  时，字节序列被按 ISO-8859-1 单字节解码再经 Windows GBK/CP936 渲染，就会产生形如
 *  "æ STº² æç°å CSI MW" 的经典 mojibake（中文乱码）。
 *
 *  解决策略（主动阻断）：
 *  - 覆盖 BlockItem.getDefaultInstance()，在返回 ItemStack 前显式 remove()
 *    BLOCK_ENTITY_DATA 组件，使外部模组得不到任何 BlockEntity 数据，自然不会追加乱码
 *  - 对 Cargo / CargoDetector / CargoGenerator / CargoStation 都使用此类注册，
 *    保证创造栏纯净无乱码
 *  - 同时不影响玩家放置后 BlockEntity 的正常生成（放置由 Block.newBlockEntity() 负责）
 *  - 例外：由调试货箱显式生成的货箱物品带 BLOCK_ENTITY_DATA，是有意配置；
 *    getDefaultInstance 仅清理默认实例，显式 set 的组件保留，并由 appendHoverText 摘要显示
 */
public class CleanBlockItem extends BlockItem {

    public CleanBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();
        // 移除 BLOCK_ENTITY_DATA DataComponent，避免 Jade/Sable 等读取脏 NBT 产生乱码 tooltip
        stack.remove(DataComponents.BLOCK_ENTITY_DATA);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // 仅货箱、且携带调试配置（BLOCK_ENTITY_DATA）时摘要显示，物品栏即可核对配置
        if (!(getBlock() instanceof CargoBlock)) return;
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return;
        try {
            HolderLookup.Provider registries = context.registries();
            CompoundTag tag = data.copyTag();
            if (!tag.contains("CargoData")) return;
            CargoData cargo = new CargoData();
            cargo.load(tag.getCompound("CargoData"), registries);
            tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.source",
                            Component.translatable(StationType.byId(cargo.getSourceStationType()).getTranslationKey()))
                    .withStyle(ChatFormatting.GOLD));
            if (cargo.hasTarget()) {
                BlockPos pos = cargo.getTargetPos();
                tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.target",
                                Component.translatable("create_cargo_dispatch.station_short."
                                        + cargo.getTargetStationType()),
                                pos.getX(), pos.getY(), pos.getZ())
                        .withStyle(ChatFormatting.GREEN));
            } else {
                tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.no_target")
                        .withStyle(ChatFormatting.GRAY));
            }
            String orderId = cargo.getOrderId();
            tooltip.add(Component.translatable("create_cargo_dispatch.debug_cargo.tip.order",
                            orderId == null || orderId.isEmpty() ? "-" : orderId)
                    .withStyle(ChatFormatting.AQUA));
        } catch (Throwable ignored) {
            // 损坏配置不影响物品正常使用
        }
    }
}
