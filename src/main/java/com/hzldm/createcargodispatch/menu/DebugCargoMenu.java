package com.hzldm.createcargodispatch.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 调试货箱编辑菜单：纯配置页（无槽位，不显示玩家背包，也不能手动放物品）。
 * 货物由玩家选择源类型 + 货物池物品后，在「生成货箱」时自动填充。
 * 字段通过自定义 payload 即时保存到主手调试物品组件。
 */
public class DebugCargoMenu extends AbstractContainerMenu {

    /** 服务端构造 */
    public DebugCargoMenu(int containerId, Inventory playerInventory) {
        super(com.hzldm.createcargodispatch.registry.ModMenuTypes.DEBUG_CARGO.get(), containerId);
        Player player = playerInventory.player;
        if (player instanceof ServerPlayer serverPlayer) {
            // 打开即下发字段与目标站候选（同时会把缺失的货物选择规范化为类型池首项）
            com.hzldm.createcargodispatch.network.ModPayloads.sendDebugCargoSync(
                    serverPlayer.serverLevel(), serverPlayer);
        }
    }

    /** 客户端构造（无额外数据） */
    public DebugCargoMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        super(com.hzldm.createcargodispatch.registry.ModMenuTypes.DEBUG_CARGO.get(), containerId);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
