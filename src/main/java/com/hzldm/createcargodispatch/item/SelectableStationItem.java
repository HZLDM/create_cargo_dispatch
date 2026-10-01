package com.hzldm.createcargodispatch.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 可转换通用方块物品（货运站 / 货箱生成器 / 货物检测器）
 *
 * 原理：
 *  - 创造栏只放 3 个通用物品，玩家右键打开「类型选择页」（客户端 Screen），
 *    选定类型后发送 ConvertStationItemPayload，由服务端把主手物品替换为对应专属 BlockItem。
 *  - 通用物品【永远不能直接放置】：右键（无论是否对准方块）一律拦截放置并打开选择页，
 *    只有先选择类型被转换为专属物品（如 farm_station）后才能正常放置。
 *  - 客户端分支通过全限定名引用 Screen 类（懒加载），服务端不执行该分支，无 ClassNotFound 风险。
 */
public class SelectableStationItem extends CleanBlockItem {

    /** 该通用物品属于哪一类方块（决定转换目标集合） */
    public enum ItemKind { STATION, GENERATOR, DETECTOR }

    private final ItemKind kind;

    public SelectableStationItem(Block block, Properties properties, ItemKind kind) {
        super(block, properties);
        this.kind = kind;
    }

    public ItemKind getKind() {
        return kind;
    }

    /** 右键对准方块：拦截放置（未选择类型不允许放置），打开类型选择页（客户端） */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            com.hzldm.createcargodispatch.client.StationTypeSelectScreen.open(kind);
        }
        return InteractionResult.SUCCESS; // 两端都拦截，不放置
    }

    /** 右键（未对准方块）：同样打开类型选择页（客户端） */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            com.hzldm.createcargodispatch.client.StationTypeSelectScreen.open(kind);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    /** 创造栏展示时追加提示：右键选择类型 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> tooltipComponents,
                                net.minecraft.world.item.TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(net.minecraft.network.chat.Component.translatable(
                "create_cargo_dispatch.item.selectable.tooltip"));
    }
}
