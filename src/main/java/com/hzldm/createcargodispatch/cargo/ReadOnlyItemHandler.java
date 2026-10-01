package com.hzldm.createcargodispatch.cargo;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nonnull;

/**
 * 只读物品处理器包装器
 *
 * 原理：
 *  - 包装一个 IItemHandler，只允许插入（insertItem），不允许提取（extractItem）
 *  - 用于货箱方块，保护货物不被漏斗等方块输出
 *  - getSlots 和 getStackInSlot 仍然可读，用于显示和检测
 *
 * 安全设计：
 *  - extractItem 始终返回 ItemStack.EMPTY
 *  - 不影响插入逻辑
 */
public class ReadOnlyItemHandler implements IItemHandler {

    private final IItemHandler inner;

    public ReadOnlyItemHandler(IItemHandler inner) {
        this.inner = inner;
    }

    /** 获取被包装的 IItemHandler（用于检测 inventory 实例是否变化） */
    public IItemHandler getWrapped() {
        return inner;
    }

    @Override
    public int getSlots() {
        return inner.getSlots();
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        return inner.getStackInSlot(slot);
    }

    /**
     * 插入物品：允许
     */
    @Override
    @Nonnull
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        return inner.insertItem(slot, stack, simulate);
    }

    /**
     * 提取物品：始终禁止，返回 EMPTY 保护货物
     */
    @Override
    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return inner.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return inner.isItemValid(slot, stack);
    }
}
