package com.hzldm.createcargodispatch.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 机器红石脉冲器：触发后方块作为强度 15 的信号源持续 {@value #PULSE_TICKS} tick，随后自动熄灭。
 *
 * 原理：
 *  - 脉冲态镜像到方块 LIT 属性，邻接电路经 Block#getSignal 读到强度 15
 *  - 熄灭时主动 updateNeighborsAt，避免相邻电路卡在高电平
 *  - 服务端 tick 驱动倒计时；BlockEntity 只需在 serverTick 调一次 {@link #tick}
 *  - 状态由 BE 持有实例保存，方块状态替换（setBlock）不影响倒计时
 */
public final class RedstonePulser {

    public static final int PULSE_TICKS = 20;

    private long pulseEndGameTick = -1L;

    /** 触发（或在脉冲中刷新持续时间） */
    public void trigger(Level level, BlockPos pos, BlockState state, BooleanProperty litProperty) {
        pulseEndGameTick = level.getGameTime() + PULSE_TICKS;
        if (!state.getValue(litProperty)) {
            level.setBlock(pos, state.setValue(litProperty, true), Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }

    /** BlockEntity serverTick 调用：倒计时结束熄灭并通知邻居重算信号 */
    public void tick(Level level, BlockPos pos, BlockState state, BooleanProperty litProperty) {
        if (pulseEndGameTick < 0L || level.getGameTime() < pulseEndGameTick) return;
        pulseEndGameTick = -1L;
        if (state.getValue(litProperty)) {
            level.setBlock(pos, state.setValue(litProperty, false), Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }
}
