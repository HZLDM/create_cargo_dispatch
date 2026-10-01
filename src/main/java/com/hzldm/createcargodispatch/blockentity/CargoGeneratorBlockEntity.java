package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.block.CargoGeneratorBlock;
import com.hzldm.createcargodispatch.cargo.CargoConnectorPresence;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoGeneratorRegistry;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.CargoPhysicsHelper;
import com.hzldm.createcargodispatch.cargo.OrderData;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import com.hzldm.createcargodispatch.redstone.RedstonePulser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 货物生成器 BlockEntity
 *
 * 职责：
 *  - 接单后在生成器上方放置单个货箱方块并物理化
 *  - 定期触发订单池刷新（每 3 游戏日）
 *  - 不再自动生成检测器——货物由目标类型的已有检测器接收
 */
public class CargoGeneratorBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Generator");

    /** 货运站类型（由结构处理器设置） */
    private StationType stationType = StationType.GENERIC;
    /** 货运站编号（同一货运站内的所有方块共享） */
    private String stationId = "";

    /** 生成成功红石脉冲（状态由脉冲器自持） */
    private final RedstonePulser pulser = new RedstonePulser();

    /** 出货模式（默认地面直接生成） */
    private volatile GeneratorSpawnMode spawnMode = GeneratorSpawnMode.GROUND;
    /** 客户端是否显示红色检测范围（Shift+右键切换，持久化并随更新包同步） */
    private boolean showRange = false;

    /** 子类传入不同 BlockEntityType */
    protected CargoGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CargoGeneratorBlockEntity(BlockPos pos, BlockState state) {
        this(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO_GENERATOR.get(), pos, state);
    }

    public void serverTick(net.minecraft.world.level.Level level, BlockPos pos) {
        // 生成成功红石脉冲倒计时（订单刷新调度已移到 OrderManager 全局 tick）
        pulser.tick(level, pos, getBlockState(), CargoGeneratorBlock.LIT);
    }

    /** 成功生成货箱后触发 20 tick、强度 15 的红石脉冲 */
    public void emitRedstonePulse() {
        pulser.trigger(level, getBlockPos(), getBlockState(), CargoGeneratorBlock.LIT);
    }

    public StationType getStationType() {
        return stationType;
    }

    public void setStationType(StationType type) {
        this.stationType = type;
        setChanged();
    }

    public String getStationId() {
        return stationId;
    }

    public void setStationId(String id) {
        this.stationId = id;
        setChanged();
    }

    public GeneratorSpawnMode getSpawnMode() {
        return spawnMode;
    }

    /** 设置出货模式并同步客户端（BE 更新包） */
    public void setSpawnMode(GeneratorSpawnMode mode) {
        this.spawnMode = mode != null ? mode : GeneratorSpawnMode.GROUND;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public boolean isShowRange() {
        return showRange;
    }

    /** 切换红色范围框显示，返回切换后状态；切换后主动同步 BE 更新包到客户端 */
    public boolean toggleRangeDisplay() {
        showRange = !showRange;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return showRange;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 兜底：如果 processor 没触发，自动推断类型和编号
        StationTypeResolver.Result res = StationTypeResolver.resolveIfNeeded(this, stationType, stationId);
        if (res != null) {
            this.stationType = res.stationType();
            this.stationId = res.stationId();
            setChanged();
            // 推送新解析的类型/编号到客户端
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.registerGenerator(level, getBlockPos());
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.unregisterGenerator(level, getBlockPos());
        }
        super.setRemoved();
    }

    /**
     * 生成货物（由 CargoStation 接单后委托调用）。
     *
     * 流程：原子接单 → 出货模式闸门：
     *  - 连接器模式：范围内必须有「红石激活且空闲」的连接器，否则接单失败（订单放回 PENDING）；
     *  - GROUND 模式：地面生成并独立物理化。
     */
    public OrderData generateCargo(ServerPlayer player, String orderId) {
        if (!OrderManager.acceptOrder(orderId, player.getUUID())) {
            return null;
        }
        OrderData order = OrderManager.getOrder(orderId);
        if (order == null) {
            return null;
        }
        if (spawnMode == GeneratorSpawnMode.CONNECTOR && level instanceof ServerLevel sl) {
            // 无可用连接器（不在范围/未红石激活/已被占用）：直接接单失败，订单放回 PENDING
            com.hzldm.createcargodispatch.cargo.ConnectorTarget target =
                    CargoConnectorPresence.findConnectorTarget(sl, ScanVolumes.centerAbove(getBlockPos()));
            if (target == null) {
                OrderManager.cancelOrder(orderId, sl);
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "create_cargo_dispatch.order.connector_unavailable"));
                return null;
            }
            return buildDirectlyOnVehicle(order, player, target);
        }
        // GROUND 模式：地面生成并独立物理化
        return buildAndAssembleAcceptedOrder(order, player);
    }

    /**
     * 连接器模式：货箱直接放进载具 plot，与载具一体。
     *
     * <p>失败（站点缺失/载具内空间不足/异常）统一 cancelOrder 回 PENDING 并恢复订单字段。
     */
    private OrderData buildDirectlyOnVehicle(OrderData order, ServerPlayer player,
                                              com.hzldm.createcargodispatch.cargo.ConnectorTarget target) {
        final String orderId = order.getOrderId();
        ServerLevel serverLevel = (level instanceof ServerLevel sl) ? sl : null;
        if (serverLevel == null) return null;

        final BlockPos snapStartPos = order.getStartPos();
        final BlockPos snapTargetPos = order.getTargetPos();
        final net.minecraft.resources.ResourceLocation snapStartDim = order.getStartDimension();
        final net.minecraft.resources.ResourceLocation snapTargetDim = order.getTargetDimension();
        final StationType snapStationType = order.getStationType();
        final StationType snapTargetStationType = order.getTargetStationType();

        boolean success = false;
        try {
            // 站点双重存在性校验（与 GROUND 同口径，chunk 未加载保守通过）
            if (!OrderManager.targetStationExistsIfLoaded(serverLevel, order)) {
                OrderManager.cancelOrder(orderId, serverLevel);
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "create_cargo_dispatch.order.station_removed_while_accepting"));
                }
                return null;
            }
            if (player != null) {
                com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.bind(orderId, player.getUUID());
            }

            CargoData cargoData = new CargoData();
            cargoData.setOrderId(orderId);
            cargoData.setTargetPos(order.getTargetPos());
            cargoData.setTargetDimension(order.getTargetDimension());
            cargoData.setSourceStationType(stationType.getId());
            cargoData.setTargetStationType(order.getTargetStationType().getId());
            cargoData.setTargetStationId(order.getTargetStationId());
            cargoData.setTransportType(order.getTransportType().getId());
            cargoData.setDimensions(order.getDimensions());

            // 直接在载具 plot 内放置货箱（按订单尺寸）
            VehicleCargoPlacer.Result placed = VehicleCargoPlacer.place(
                    serverLevel, target, stationType, cargoData,
                    order.getCargoItemId(), order.getCargoCount(), order.getDimensions());
            if (placed == null) {
                // 载具 plot 内空间不足：订单回 PENDING，不占用连接器
                OrderManager.cancelOrder(orderId, serverLevel);
                if (player != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "create_cargo_dispatch.order.accept_failed"));
                }
                return null;
            }

            // 取主方块 inventory（位于 plot inner），注册附着型货箱
            Level inner = SubLevelScanner.getSubLevelInternalLevel(target.vehicle());
            SimpleContainer inventory = null;
            if (inner != null && inner.getBlockEntity(placed.mainPos()) instanceof CargoBlockEntity cbe) {
                inventory = cbe.getInventory();
            }
            CargoManager.registerAttachedCargo(target.vehicleUuid(), placed.mainPos(),
                    placed.gridPositions(), inventory, cargoData);
            // 记录载具 UUID：收货/取消据此定位，删除路径靠附着标记保证只删方块不删载具
            order.setSubLevelUuid(target.vehicleUuid());

            // 同步连接器 BE 自身吸附状态：红石 OFF 时据此触发切割；同时占用同一连接器防止重复派单
            bindConnectorState(target);

            success = true;
            emitRedstonePulse();
            return order;
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] buildDirectlyOnVehicle 异常回滚 玩家={}, order={}",
                    player != null ? player.getName().getString() : "null", orderId, t);
            try { OrderManager.cancelOrder(orderId, serverLevel); } catch (Throwable ignore) {}
            if (player != null) {
                try {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "create_cargo_dispatch.order.accept_failed"));
                } catch (Throwable ignore) {}
            }
            return null;
        } finally {
            if (!success) {
                order.setStartPos(snapStartPos);
                order.setTargetPos(snapTargetPos);
                order.setStartDimension(snapStartDim);
                order.setTargetDimension(snapTargetDim);
                order.setStationType(snapStationType);
                order.setTargetStationType(snapTargetStationType);
                order.setSubLevelUuid(null);
                order.setAcceptedPlayer(null);
                order.setStatus(OrderData.Status.PENDING);
            }
        }
    }

    /**
     * 货箱放置成功后同步连接器 BE 的吸附状态。
     * 原理：红石 OFF 时策略以 state.attached 为触发条件，不同步则断开永不触发；
     *       且连接器占用闸门（isAttached）也会失效，可能被重复派单。
     */
    private void bindConnectorState(com.hzldm.createcargodispatch.cargo.ConnectorTarget target) {
        try {
            Level inner = SubLevelScanner.getSubLevelInternalLevel(target.vehicle());
            if (inner != null
                    && inner.getBlockEntity(target.connectorPos()) instanceof CargoConnectorBlockEntity connector) {
                CargoConnectorSerializer.State st = connector.state();
                st.attached = true;
                st.vehicleUuid = target.vehicleUuid();
                // 同体型货箱无独立 UUID，用载具 UUID 占位；断开切割后替换为新独立货箱 UUID
                st.cargoUuid = target.vehicleUuid();
                connector.markChangedAndSync();
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] 绑定连接器状态失败 order", t);
        }
    }

    /**
     * GROUND 模式：对「已接单」订单执行地面货箱建造与独立物理化。
     */
    private OrderData buildAndAssembleAcceptedOrder(OrderData order, ServerPlayer player) {
        final String orderId = order.getOrderId();
        // =============== 关键修复：生成前保存订单「关键字段快照」 ===============
        // 为什么必须做？
        //   - 本方法中途 L230 order.setStartPos(cargoPos) 会「永久修改 OrderData 对象上的 startPos」（因为 ACCEPTED/PENDING
        //     池里存的就是同一个对象引用，没有副本）
        //   - 后面任何「return null / 抛异常」都会经由 cancelOrder 把这个对象放回 PENDING 池，但 resetForReturnToPending 按注释
        //     「绝不在此乱改 startPos/targetPos」不敢碰位置字段
        //   - 最终结果：PENDING 池里的订单 startPos 变成了 cargoPos（生成器上方 2 格的临时位置，不是真正 detector/站本体），
        //     下次玩家点「接单」时，handleAcceptOrder.verifyStation 按这个临时位置扫结构方块扫不到 → 直接拒单！
        //     表面现象就是：「货运站显示了可是无法接单」/「取消后显示但怎么点都提示站点缺失」
        // =============== 另外：包 try/catch/finally 做失败回滚兜底 ===============
        final BlockPos snapStartPos = order.getStartPos();
        final BlockPos snapTargetPos = order.getTargetPos();
        final net.minecraft.resources.ResourceLocation snapStartDim = order.getStartDimension();
        final net.minecraft.resources.ResourceLocation snapTargetDim = order.getTargetDimension();
        final com.hzldm.createcargodispatch.cargo.StationType snapStationType = order.getStationType();
        final com.hzldm.createcargodispatch.cargo.StationType snapTargetStationType = order.getTargetStationType();

        ServerLevel serverLevel = (level instanceof ServerLevel sl) ? sl : null;
        boolean success = false;
        try {

        // —— 起始站 + 目标站 双重存在性校验：
        // 订单可能在「显示在待接单列表」→「玩家点击接单」之间，起始站或目标站被玩家拆掉
        // OrderManager.targetStationExistsIfLoaded 已重定向到新方法 sourceAndTargetStationsExistIfLoaded，
        // 会同时校验 order.startPos（起始货运站方块） + order.stationType，
        // 以及      order.targetPos（目标货运站方块） + order.targetStationType
        // chunk 未加载时保守通过，防止加载卸载 chunk 导致合法订单被误拦。
        if (serverLevel != null) {
            // 1) 先校验方块还存在（老逻辑）
            if (!com.hzldm.createcargodispatch.cargo.OrderManager.targetStationExistsIfLoaded(serverLevel, order)) {
                // 接受失败：订单 acceptOrder 已移到 ACCEPTED，但站点真的拆了，只能放回 PENDING
                //  —— 之前调 dropAcceptedOrderForDeadStation 是「丢弃（不放回PENDING）」，这就是「取消后无法再接」的致命BUG
                //     因为 generateCargo L116 已经成功调过 acceptOrder（PENDING→ACCEPTED），
                //     dropAcceptedOrderForDeadStation 只会 removeAcceptedOrder，既不 addPendingOrder 也不调 afterPendingChanged 广播，
                //     订单直接消失了，玩家取消/放弃回来重新接就再也看不到订单！
                com.hzldm.createcargodispatch.cargo.OrderManager.cancelOrder(orderId, serverLevel);
                if (player != null) {
                    player.sendSystemMessage(
                            net.minecraft.network.chat.Component.translatable("create_cargo_dispatch.order.station_removed_while_accepting"));
                }
                return null;
            }
            // 2) 【口径修正】不再强制要求接单玩家「同时连接起点站与终点站」。
            // 原理：handleAcceptOrder 已把「已连接站点集合」降级为"UI 可见性过滤"，明确不再作为接单门槛；
            //       这里若再校验 isStationConnected，会误伤 /ccd generateorder 指令生成等未走连接链路的订单——
            //       handleAcceptOrder 已放行、generateCargo 却又拒绝（玩家看到"站点未连接"接单失败）。
            //       站存在性已由上面 sourceAndTargetStationsExistIfLoaded 兜底，断开站的取消由
            //       LinkageManager.disconnectStation/removeStationByPos → cancelAcceptedOrdersForCompanyAndStation 负责。
            //       因此此处不再重复校验连接，与 handleAcceptOrder 新规则保持完全一致。
        }

        // 绑定订单与玩家：用于货物到达目标检测器后通知该玩家删除路径点（补生成时玩家可能离线）
        if (player != null) {
            com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.bind(orderId, player.getUUID());
        }

        if (level != null && !level.isClientSide() && serverLevel != null) {
            // ★ 懒重建 9合1 配方缓存：若 /reload 后标记为 dirty，在创建货箱时用服务端 RecipeManager 精准重建
            // 服务端的 RecipeManager + RegistryAccess 是所有已加载数据包的"最终真源"；registryAccess 必须传入，
            // 否则 Minecraft 1.21 的 Recipe.getResultItem 会返回 EMPTY，下界合金块等配方会被跳过。
            com.hzldm.createcargodispatch.cargo.CargoPhysicsHelper.rebuildNineToOneBlockMapIfDirty(
                    serverLevel.getRecipeManager(), serverLevel.registryAccess());

            BlockPos generatorPos = getBlockPos();

            // —— 每个站点独立发单，只支持「起点站接单 → 货物发往订单终点站」：不搞回程反向运输！
            // 货箱生成在起点站（当前生成器上方），目的地就是订单定义的 targetPos（终点站），不支持反向。
            CargoData cargoData = new CargoData();
            cargoData.setOrderId(order.getOrderId());
            cargoData.setTargetPos(order.getTargetPos());
            cargoData.setTargetDimension(order.getTargetDimension());
            cargoData.setSourceStationType(stationType.getId());
            cargoData.setTargetStationType(order.getTargetStationType().getId());
            // 目标站编号随货箱走：检测器收货/手动提交时据此严格校验，货箱无法在非目标编号站点提交
            cargoData.setTargetStationId(order.getTargetStationId());
            cargoData.setTransportType(order.getTransportType().getId());
            cargoData.setDimensions(order.getDimensions());

            // 主方块位置（生成器上方 2 格，作为结构中心）
            BlockPos cargoPos = generatorPos.above(2);

            // 根据生成器自身 FACING 确定货箱长轴方向
            // 原理：generator.FACING 是放置时记录的水平朝向（NORTH/SOUTH/EAST/WEST）
            //       货箱长轴应与生成器朝向绑定，而不是玩家"点接单按钮那一刻"的朝向（玩家可能已转身）
            //       保证放置时结构朝向一致、延伸方向可预测
            net.minecraft.core.Direction.Axis cargoAxis;
            {
                BlockState state = getBlockState();
                if (state.getBlock() instanceof com.hzldm.createcargodispatch.block.CargoGeneratorBlock generatorBlock
                        && state.hasProperty(com.hzldm.createcargodispatch.block.CargoGeneratorBlock.FACING)) {
                    cargoAxis = state.getValue(com.hzldm.createcargodispatch.block.CargoGeneratorBlock.FACING).getAxis();
                } else if (player != null) {
                    cargoAxis = player.getDirection().getAxis();
                } else {
                    cargoAxis = net.minecraft.core.Direction.Axis.Z;
                }
            }

            // 检查结构空间是否足够（按订单尺寸）
            if (!com.hzldm.createcargodispatch.cargo.CargoStructureHelper.isSpaceAvailable(
                    pos -> level.getBlockState(pos), cargoPos, cargoAxis, order.getDimensions())) {
                // 空间不足，取消订单并放回 PENDING 池，同时广播同步（防止其他玩家 UI 上残留"已接单"状态不刷新）
                OrderManager.cancelOrder(orderId, serverLevel);
                return null;
            }

            // 根据 StationType 获取对应专属货箱方块
            var cargoBlockSupplier = com.hzldm.createcargodispatch.cargo.CargoStructureHelper.getCargoBlock(stationType);
            // 设置 HORIZONTAL_AXIS 属性，让方块模型朝向正确
            BlockState cargoState = cargoBlockSupplier.get().defaultBlockState()
                    .setValue(com.hzldm.createcargodispatch.block.CargoBlock.HORIZONTAL_AXIS, cargoAxis);

            // 生成多方块结构（按订单尺寸，居中对称）
            List<BlockPos> placedPositions = com.hzldm.createcargodispatch.cargo.CargoStructureHelper.generateStructurePositions(
                    cargoPos, cargoAxis, order.getDimensions());
            // 防挤压放置：空间检查与放置的竞态窗口内若进入了方块，顶出时掉落其掉落物而非静默消失
            for (BlockPos pos : placedPositions) {
                com.hzldm.createcargodispatch.cargo.CargoStructureHelper.placeOrEvict(
                        level, pos, cargoState, Block.UPDATE_ALL);
            }

            // 注意：此处【不再】把 order.setStartPos(cargoPos)。
            // 原理：订单的 startPos/targetPos 语义是「货运站位置」（生成订单时写入的站/detector 坐标），
            //       删除货箱时由 removeCargoBlocks 通过 SubLevel UUID 或 CargoManager.findStartPosByOrderId
            //       反查真实货箱位置，不需要靠污染订单 startPos。
            //       之前永久改写 startPos 会导致：订单被取消/放弃放回 PENDING 后，起点变成货箱临时位置，
            //       后续接单校验 / 客户端显示 / 站删除清理都依赖 fallback 扫描兜底，属于"能工作但不干净"的脏状态。

            // 把 cargoData 设置到所有 27 个方块的 CargoBlockEntity
            // 原理：Sable 装配时通过 AssemblyTransform.apply(BlockPos) 变换坐标
            //       SubLevel 内部方块坐标可能与原始坐标不同（旋转/偏移）
            //       无法在装配后用原始坐标遍历恢复数据
            //       但 Sable 的 moveBlocks 会保留 BlockEntity 的 NBT 数据
            //       所以在装配前把 cargoData 写入所有方块，装配后自动保留
            for (BlockPos pos : placedPositions) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof CargoBlockEntity cargoBE) {
                    cargoBE.setCargoData(cargoData);
                    // 只有 cargoPos（底部中心）是主方块，用于 BlockEntityRenderer 渲染整个 3x3x9 模型
                    cargoBE.setMainBlock(pos.equals(cargoPos));
                    cargoBE.setChanged();
                }
            }
            // 主动同步主方块数据到客户端
            level.sendBlockUpdated(cargoPos, cargoState, cargoState, 3);

            // 注入物品到主方块（只有主方块持有 inventory 引用）
            injectItemsIntoCargo(cargoPos, order);

            // 关键修复：把主方块的 inventory 内容复制到所有 27 个方块
            // 原因：
            //   - Create 护目镜的 addToGoggleTooltip 在客户端调用，无法访问服务端 CargoManager 缓存
            //   - 客户端只能读取 BlockEntity 本地同步的 NBT（getUpdateTag/saveAdditional）
            //   - Sable moveBlocks 装配时，每个方块独立保存/加载 NBT
            //   - 如果不复制，非主方块 localInventory 为空 → 护目镜显示重量=0、无物品
            SimpleContainer mainInventory = null;
            BlockEntity mainBE = level.getBlockEntity(cargoPos);
            if (mainBE instanceof CargoBlockEntity cargoBE) {
                mainInventory = cargoBE.getInventory();
            }
            if (mainInventory != null) {
                for (BlockPos pos : placedPositions) {
                    if (pos.equals(cargoPos)) continue; // 主方块跳过，已经有了
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof CargoBlockEntity cargoBE) {
                        SimpleContainer targetInv = cargoBE.getInventory();
                        targetInv.clearContent();
                        for (int i = 0; i < mainInventory.getContainerSize(); i++) {
                            ItemStack s = mainInventory.getItem(i);
                            if (!s.isEmpty()) {
                                targetInv.setItem(i, s.copy());
                            }
                        }
                        cargoBE.setChanged();
                        // 主动同步到客户端（SubLevel中护目镜/右键查看依赖）
                        level.sendBlockUpdated(pos, cargoState, cargoState, 3);
                    }
                }
            }
            // 同步主方块
            level.sendBlockUpdated(cargoPos, cargoState, cargoState, 3);

            // 装配前缓存 inventory（装配后方块被移走，主世界 BlockEntity 变为 null）
            SimpleContainer cachedInventory = null;
            BlockEntity beBeforeAssemble = level.getBlockEntity(cargoPos);
            if (beBeforeAssemble instanceof CargoBlockEntity cargoBE) {
                cachedInventory = cargoBE.getInventory();
            }

            // 装配为物理化 SubLevel（传入 27 个位置，Sable 自动识别 3x3x9 大小）
            String cargoName = "货物 #" + order.getOrderId();
            Object subLevel = CargoPhysicsHelper.assembleBlocks(serverLevel, cargoPos, placedPositions, cargoName);
            if (subLevel == null) {
                LOGGER.warn("[CargoDispatch] Sable 装配失败，保留为静态方块");
            } else {
                UUID subLevelUuid = SubLevelScanner.getSubLevelUuid(subLevel);
                if (subLevelUuid != null) {
                    // 用装配前缓存的 inventory 建立快照
                    CargoManager.registerSubLevel(subLevelUuid, cargoPos, placedPositions, cachedInventory, cargoData);
                    order.setSubLevelUuid(subLevelUuid);
                }
            }

            CargoManager.register(cargoPos, cargoData);
        }
        success = true;
        // 货箱成功生成：发射 20 tick、强度 15 红石脉冲
        emitRedstonePulse();
        return order;
        } catch (Throwable t) {
            // 兜底：任何 RuntimeException 导致中途跳出（方块放置异常、装配异常等），
            //      强制调 cancelOrder 把订单从 ACCEPTED 放回 PENDING，避免「卡在 ACCEPTED → GUI 没了」
            LOGGER.error("[CargoDispatch] generateCargo 异常回滚 玩家={}, order={}",
                    (player != null ? player.getName().getString() : "null"), orderId, t);
            if (serverLevel != null) {
                try { OrderManager.cancelOrder(orderId, serverLevel); } catch (Throwable ignore) {}
            }
            if (player != null) {
                try {
                    player.sendSystemMessage(
                            net.minecraft.network.chat.Component.translatable("create_cargo_dispatch.order.accept_failed"));
                } catch (Throwable ignore) {}
            }
            return null;
        } finally {
            // =============== 无论失败方式：return null / 抛异常 → 都恢复订单关键字段 ===============
            // 为什么 finally 一定能纠正？
            //  - 「return null 前已 cancelOrder → addPendingOrder」：同一个 order 对象引用已经在 PENDING 池里，
            //    这里 setStartPos(快照) 直接改池里对象的字段，下次玩家点开 GUI / 接单校验拿到的就是原位置 ✓
            //  - 「catch 兜底刚调 cancelOrder」：同理，字段在 finally 里修正，池里即刻干净 ✓
            //  - 「未成功生成货箱 SubLevel」：老的 SubLevelUuid 必须抹掉，避免下一次接单误以为已装配 ✓
            if (!success) {
                order.setStartPos(snapStartPos);
                order.setTargetPos(snapTargetPos);
                order.setStartDimension(snapStartDim);
                order.setTargetDimension(snapTargetDim);
                order.setStationType(snapStationType);
                order.setTargetStationType(snapTargetStationType);
                order.setSubLevelUuid(null);
                // 兜底：acceptedPlayer 万一没被 cancelOrder 清掉，这里也给清掉避免脏数据
                order.setAcceptedPlayer(null);
                // 兜底：强行写回 PENDING 状态（cancelOrder 已经把 Status→PENDING，这里防万一）
                order.setStatus(com.hzldm.createcargodispatch.cargo.OrderData.Status.PENDING);
            }
        }
    }

    /**
     * 将订单物品注入到货箱的 inventory 中
     */
    private void injectItemsIntoCargo(BlockPos startPos, OrderData order) {
        if (level == null || order.getCargoItemId() == null || order.getCargoItemId().isEmpty()) {
            return;
        }
        Item item = getItemById(order.getCargoItemId());
        if (item == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(startPos);
        if (!(be instanceof CargoBlockEntity cargoBE)) {
            return;
        }
        IItemHandler handler = cargoBE.getWritableItemHandler();
        int remaining = order.getCargoCount();
        int maxStackSize = item.getDefaultMaxStackSize();
        for (int i = 0; i < handler.getSlots() && remaining > 0; i++) {
            int stackSize = Math.min(remaining, maxStackSize);
            ItemStack stack = new ItemStack(item, stackSize);
            ItemStack leftover = handler.insertItem(i, stack, false);
            remaining -= (stackSize - leftover.getCount());
        }
    }

    private Item getItemById(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.parse(itemId);
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("StationType", stationType.getId());
        if (!stationId.isEmpty()) {
            tag.putString("StationId", stationId);
        }
        GeneratorProgressionSerializer.save(tag, spawnMode, showRange);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 仅在 nbt 显式包含 StationType 时才覆盖（防止空串覆盖子类构造器设置的类型）
        if (tag.contains("StationType")) {
            stationType = StationType.byId(tag.getString("StationType"));
        }
        stationId = tag.getString("StationId");
        spawnMode = GeneratorProgressionSerializer.readMode(tag);
        showRange = GeneratorProgressionSerializer.readShowRange(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
