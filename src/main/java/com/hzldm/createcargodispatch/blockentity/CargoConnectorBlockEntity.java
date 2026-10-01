package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.block.CargoConnectorBlock;
import com.hzldm.createcargodispatch.blockentity.CargoConnectorSerializer.State;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * 货物连接器 BlockEntity，同时是 {@link CargoConnectorStrategy.Context}。
 *
 * <p>启用状态<b>单一真源 = BlockState 的 POWERED</b>（红石是否通电），
 * BE 不再另存 enabled：红石变化只需一次 setBlock，BE/渲染/装配自动一致。
 *
 * <p>Sable 装配载具时 blockstate 原样移入 plot，POWERED 保留，
 * 装配前的红石启用状态会正确带入物理化结构。
 */
public class CargoConnectorBlockEntity extends BlockEntity
        implements dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor, CargoConnectorStrategy.Context {

    /** attach 路径（吸附地面静止货箱）的运行时状态 */
    private final State state = new State();
    private long lastActionTick = -1L;
    /** 策略最近一次被驱动的游戏 tick：sable$tick 与普通 serverTick 同 tick 去重，避免双跑 */
    private long lastStrategyTick = -1L;

    public CargoConnectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO_CONNECTOR.get(), pos, blockState);
    }

    // ---- 启用状态：直接读 blockstate（单一真源） ----

    /** 连接器是否被红石激活 */
    public boolean isEnabled() {
        return getBlockState().getValue(CargoConnectorBlock.POWERED);
    }

    /** 是否处于 attach 吸附态 */
    public boolean isAttached() {
        return state.attached;
    }

    /**
     * Sable 物理 tick 回调：作为策略驱动的冗余路径（actor 已登记时）。
     * 主驱动是普通 {@link #serverTick}——重进后 actor 集合可能为空导致此回调不触发，
     * 但主世界的 BE ticker 每 tick 可靠运行，功能不再受其影响。
     */
    public void sable$tick(dev.ryanhcode.sable.sublevel.ServerSubLevel subLevel) {
        if (level instanceof ServerLevel sl) {
            runStrategy(sl, subLevel);
        }
    }

    /** 驱动策略（同游戏 tick 去重），sable$tick / serverTick 共用 */
    private void runStrategy(ServerLevel sl, Object vehicle) {
        if (vehicle == null) return;
        long now = sl.getGameTime();
        if (now == lastStrategyTick) return; // 同 tick 已由另一路径驱动
        lastStrategyTick = now;
        CargoConnectorStrategy.tick(this, vehicle);
    }

    /**
     * 解析连接器所属载具 SubLevel：
     * 优先用已绑定的 vehicleUuid；未绑定/失效时用 Sable.HELPER.getContaining 按位置兜底
     * （连接器已装配进载具、但接单前 state.vehicleUuid 尚未设置的情况）。
     */
    private Object resolveVehicle(ServerLevel sl) {
        if (state.vehicleUuid != null) {
            Object v = SubLevelScanner.findSubLevelByUuid(sl, state.vehicleUuid);
            if (v != null) return v;
        }
        return SubLevelScanner.findSubLevelContainingGlobalPos(sl, getBlockPos());
    }

    /** Context：红石是否通电（直接读 blockstate POWERED） */
    @Override
    public boolean isPowered() {
        return isEnabled();
    }

    /**
     * 重进恢复后把自己重新登记为 plot actor。
     *
     * <p>原理：Sable 重进时 plot 方块经 PalettedContainer 直接重建（不经过 LevelChunk.setBlockState），
     * plot.blockEntityActors 不会被重新填充，SubLevelPhysicsSystem 因而不再回调 sable$tick，
     * 红石 ON/OFF（连接/断开）全部失效。
     * fullyLoad 的顺序是 allocateSubLevel（载具加入容器）→ plot.load（触发本 BE.onLoad），
     * 故 onLoad 时用自身 NBT 已恢复的 vehicleUuid 必能定位载具；补触发 plot.onBlockChange，
     * 其内部识别到本 BE 是 BlockEntitySubLevelActor 即重新登记。
     * 首次放置时 state.attached=false，自动跳过。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        if (!state.attached || state.vehicleUuid == null) return;
        try {
            Object vehicle = SubLevelScanner.findSubLevelByUuid(sl, state.vehicleUuid);
            if (vehicle == null) return; // 载具不存在：交给 serverTick 的幽灵附着清理
            Object plot = SubLevelScanner.getPlot(vehicle);
            if (plot instanceof dev.ryanhcode.sable.sublevel.plot.LevelPlot levelPlot) {
                levelPlot.onBlockChange(getBlockPos(), getBlockState());
            }
        } catch (Throwable t) {
            // actor 重登记失败不得阻断 BE 加载 / 世界恢复
        }
    }

    /**
     * 普通 tick（主世界 BE ticker 驱动，重进后可靠）。
     * 0) 驱动红石策略（连接/断开）——主路径，不依赖 plot actor 集合；
     * 1) 幽灵附着对账：载具已不存在时清除 CargoManager 索引与本连接器 state；
     * 2) 常规悬空校正：同体附着且附着记录丢失时清标记。
     */
    public void serverTick(Level level, BlockPos pos, BlockState blockState) {
        if (!(level instanceof ServerLevel sl)) return;

        // 0) 主驱动：解析所属载具并跑策略（未连接 attached=false 时也要跑，用于红石 ON 吸附），
        //    走主世界 ticker，重进后可靠，不依赖 plot actor 集合
        runStrategy(sl, resolveVehicle(sl));

        if (!state.attached) return;

        // 1) 重进对账：真实载具是否被 Sable 恢复
        if (state.vehicleUuid != null
                && SubLevelScanner.findSubLevelByUuid(sl, state.vehicleUuid) == null) {
            // 幽灵附着：载具/货箱实际不存在，清除 CargoManager 索引与本连接器状态
            CargoManager.removeAttachedRecord(state.vehicleUuid);
            state.clear();
            setChanged();
            level.sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_ALL);
            return;
        }

        // 2) 常规：约束句柄丢失且附着记录也不存在 → 清标记
        if (state.constraintHandle != null) return;
        boolean stillAttached = state.vehicleUuid != null
                && CargoManager.isAttachedVehicleCargo(state.vehicleUuid);
        if (!stillAttached) {
            state.attached = false;
            setChanged();
        }
    }

    // ---- attach State 持久化（约束句柄为瞬态，重启丢失时 serverTick 校正标记） ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CargoConnectorSerializer.save(tag, state);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        State loaded = CargoConnectorSerializer.load(tag);
        // 把持久化字段拷回当前 state（约束句柄保持 null，等 serverTick 判定）
        state.attached = loaded.attached;
        state.vehicleUuid = loaded.vehicleUuid;
        state.cargoUuid = loaded.cargoUuid;
        state.originalAnchor = loaded.originalAnchor;
        state.originalCargoPos = loaded.originalCargoPos;
        state.originalCargoQuat = loaded.originalCargoQuat;
    }

    // ---- CargoConnectorStrategy.Context ----

    public ServerLevel serverLevel() {
        return level instanceof ServerLevel sl ? sl : null;
    }

    /**
     * 物理化后 plot chunk 所属 Level 仍是主世界 ServerLevel（Sable 按坐标路由方块读写），
     * 故直接返回 BE 所在 ServerLevel。
     */
    @Override
    public ServerLevel overworld() {
        return serverLevel();
    }

    @Override
    public BlockPos blockPos() {
        return getBlockPos();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public long lastActionTick() {
        return lastActionTick;
    }

    @Override
    public void setLastActionTick(long tick) {
        this.lastActionTick = tick;
    }

    @Override
    public void markChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public UUID getCargoUuid() {
        return state.cargoUuid;
    }
}
