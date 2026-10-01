package com.hzldm.createcargodispatch.block;

import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.CargoPhysicsHelper;
import com.hzldm.createcargodispatch.cargo.CargoStructureHelper;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * 货箱方块（CargoBlock）
 *
 * 原理：
 *  - 独立于机械动力的 ItemVault
 *  - 不可破坏（strength=-1, 3600000）
 *  - 实现 EntityBlock 接口，关联 CargoBlockEntity
 *  - 右键交互：手持玉/眼镜查看货运信息
 *  - 连锁破坏：玩家破坏一个货箱方块时，整个连通多方块结构都消失
 *
 * 与机械动力 ItemVault 的区别：
 *  - 只读 inventory（阻止所有提取操作，保护货物）
 *  - 不同的注册名和本地化名称（"货箱"）
 *  - 独立的 BlockEntity 类型
 */
public class CargoBlock extends Block implements EntityBlock {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-CargoBlock");

    public static final Property<Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty LARGE = BooleanProperty.create("large");

    public CargoBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LARGE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_AXIS, LARGE);
        super.createBlockStateDefinition(builder);
    }

    /** 从物品 BLOCK_ENTITY_DATA 中读取货箱尺寸（调试货箱）；无配置默认标准 3×3×9 */
    private static CargoDimensions dimsFromStack(ItemStack stack) {
        net.minecraft.world.item.component.CustomData cd = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (cd == null) return CargoDimensions.SMALL;
        net.minecraft.nbt.CompoundTag tag = cd.copyTag();
        if (tag.contains("CargoData")) {
            net.minecraft.nbt.CompoundTag cdt = tag.getCompound("CargoData");
            if (cdt.contains("Dims")) return CargoDimensions.load(cdt.getCompound("Dims"));
        }
        return CargoDimensions.SMALL;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Axis axis = context.getHorizontalDirection().getAxis();
        // 调试配置货箱（创造 + 物品携带 BLOCK_ENTITY_DATA）：必须有完整空间才允许放置
        ItemStack stack = context.getItemInHand();
        Player placer = context.getPlayer();
        if (placer != null && placer.isCreative()
                && stack.has(DataComponents.BLOCK_ENTITY_DATA)) {
            Level level = context.getLevel();
            CargoDimensions dims = dimsFromStack(stack);
            if (!CargoStructureHelper.isSpaceAvailable(level::getBlockState,
                    context.getClickedPos(), axis, dims)) {
                if (!level.isClientSide() && placer instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Component.translatable(
                            "create_cargo_dispatch.debug_cargo.no_space"));
                }
                return null;
            }
        }
        return defaultBlockState().setValue(HORIZONTAL_AXIS, axis);
    }

    /**
     * 放置后自动扩展为 3x3x9 完整多方块结构（仅创造模式 + 空间完全空），且立即物理化
     *
     * 设计原因（用户纠正后）：
     *  - 修复"创造模式的货箱不能放置多方块结构"：之前 getStateForPlacement 只返回 1 方块 state，
     *    玩家每次放货箱只得到孤零零一个方块，无法组成 3x3x9 结构。
     *  - 修复"创造模式的货箱不应该是方块化的，应该是物理化的"：setPlacedBy 完成 27 方块后，
     *    立即调用 Sable SubLevelAssemblyHelper.assembleBlocks 把它装配成物理 SubLevel（刚体），
     *    不再停留在"静态方块堆"状态（玩家不再需要先贴 Merging Glue 才会有物理行为）。
     *  - 为什么生存模式不自动扩展/不自动物理化？
     *    生存模式 BlockItem.useOn 只扣 1 个物品，如果 setPlacedBy 再免费生成 26 方块，
     *    就变成"花 1 个物品得到 27 方块"的作弊。生存模式玩家要手动放 27 方块，
     *    再使用【航空学 Merging Glue（黏着器）】把两侧 CCD 货箱对齐合并。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide()) return;
        Axis axis = state.getValue(HORIZONTAL_AXIS);
        Player player = (placer instanceof Player p) ? p : null;

        // —— 阶段 1：标记当前方块 LARGE=true（主方块位置） ——
        // LARGE=true 是"已被纳入多方块结构"的标记，防止被黏着器重复扩展
        BlockState mainState = state.setValue(LARGE, true);
        if (mainState != state) {
            level.setBlock(pos, mainState, Block.UPDATE_ALL_IMMEDIATE);
        }
        // 当前方块就是主方块（渲染整个 3x3x9 模型的锚点）
        if (level.getBlockEntity(pos) instanceof CargoBlockEntity mainBE) {
            // 显式把物品上的 BLOCK_ENTITY_DATA（调试货箱配置：Inventory+CargoData）应用到主方块。
            // 不依赖 BlockItem 内部应用时序（本方法前后存在 setBlock 重建 BE 的可能），重复应用幂等。
            mainBE.applyComponentsFromItemStack(stack);
            mainBE.setMainBlock(true);
            mainBE.setChanged();
            level.sendBlockUpdated(pos, mainState, mainState, Block.UPDATE_CLIENTS);
        }

        // 配置货箱判定：物品带 BLOCK_ENTITY_DATA（调试货箱生成），含有效 CargoData
        boolean configured = stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA) != null;

        // —— 阶段 2：创造模式 + 空间全空 → 自动补齐剩余方块 ——
        if (player != null && player.isCreative() && level instanceof ServerLevel sl) {
            CargoDimensions dims = dimsFromStack(stack);
            CargoStructureHelper.BlockGetter getter = p -> level.getBlockState(p);
            boolean spaceOk = CargoStructureHelper.isSpaceAvailable(getter, pos, axis, dims);
            LOGGER.info("[CargoCreative] 阶段2 空间检查 base={} axis={} spaceOk={} configured={} dims={}",
                    pos, axis, spaceOk, configured, dims);

            // 计划结构位置（get(0) 为主方块）
            java.util.List<BlockPos> planned = CargoStructureHelper.generateStructurePositions(pos, axis, dims);
            java.util.List<BlockPos> actual = new java.util.ArrayList<>(planned.size());
            actual.add(pos);

            if (spaceOk) {
                // 空间充足：补齐剩余 26 方块
                for (int i = 1; i < planned.size(); i++) {
                    BlockPos sp = planned.get(i);
                    level.setBlock(sp, mainState, Block.UPDATE_ALL_IMMEDIATE);
                    if (level.getBlockEntity(sp) instanceof CargoBlockEntity cbe) {
                        cbe.setMainBlock(false);
                        cbe.setChanged();
                        level.sendBlockUpdated(sp, mainState, mainState, Block.UPDATE_CLIENTS);
                    }
                    actual.add(sp);
                }
            } else if (configured) {
                // 配置货箱空间不足：收集占用区内已经是本类方块的位置（至少主方块），
                // 保证配置货箱即使只放 1 格也能物理化，绝不退回静态方块
                for (int i = 1; i < planned.size(); i++) {
                    BlockPos sp = planned.get(i);
                    if (level.getBlockState(sp).getBlock() instanceof CargoBlock) {
                        actual.add(sp.immutable());
                    }
                }
            }

            // 配置货箱必须物理化（空间足→27 格；空间不足→至少主方块）；普通创造货箱保持原逻辑（仅空间足时扩展）
            boolean shouldAssemble = spaceOk || configured;
            if (shouldAssemble) {
                for (BlockPos p : actual) {
                    CargoManager.bindSlaveToMain(p, pos);
                }
                LOGGER.info("[CargoCreative] 阶段2 完成，结构方块数={}", actual.size());

                if (level.getBlockEntity(pos) instanceof CargoBlockEntity mainBE2) {
                    CargoData data = mainBE2.getCargoData();
                    // 装配前缓存（装配后方块移入 SubLevel，主世界 BE 失效，不可再读）
                    SimpleContainer invSnapshot = mainBE2.getInventory();
                    Component customName = stack.get(DataComponents.CUSTOM_NAME);
                    // 关键：装配前把货运数据/物品/名称写入全部结构方块的 BE。
                    // Sable moveBlocks 逐方块搬运并保留各自 BE 的 NBT；装配后 SubLevel 内坐标
                    // 会被 AssemblyTransform 变换，无法再按世界原坐标补写（旧实现恢复 count=0）。
                    // 与 CargoGeneratorBlockEntity 的已验证路径保持一致。
                    for (BlockPos p : actual) {
                        if (level.getBlockEntity(p) instanceof CargoBlockEntity cbe) {
                            cbe.setCargoData(data);
                            cbe.setMainBlock(p.equals(pos));
                            if (customName != null) cbe.setCustomName(customName);
                            if (!p.equals(pos)) copyContainer(invSnapshot, cbe.getInventory());
                            cbe.setChanged();
                            BlockState ps = level.getBlockState(p);
                            level.sendBlockUpdated(p, ps, ps, Block.UPDATE_CLIENTS);
                        }
                    }
                    String name = (customName != null)
                            ? customName.getString()
                            : "CargoCreative#" + Integer.toHexString(pos.hashCode());
                    Object sub = CargoPhysicsHelper.assembleBlocks(sl, pos, actual, name);
                    LOGGER.info("[CargoCreative] 阶段3 assembleBlocks 返回 sub={} (null=装配失败)", sub);
                    if (sub != null) {
                        java.util.UUID u = SubLevelScanner.getSubLevelUuid(sub);
                        if (u != null) {
                            CargoManager.registerSubLevel(u, pos, actual, invSnapshot, data);
                            // 配置货箱（有目标+订单号）装配成功 → 自动登记为公司手工订单
                            if (configured) {
                                registerPlacedOrder(sl, player, pos, u, invSnapshot, data);
                            }
                        }
                    }
                }
            }
        }
        super.setPlacedBy(level, pos, state, placer, stack);
    }

    /** 按槽位把源容器内容复制到目标容器（同尺寸 27 格，物品栈深拷贝，避免共享引用） */
    private static void copyContainer(SimpleContainer src, SimpleContainer dst) {
        dst.clearContent();
        int n = Math.min(src.getContainerSize(), dst.getContainerSize());
        for (int i = 0; i < n; i++) {
            ItemStack s = src.getItem(i);
            if (!s.isEmpty()) dst.setItem(i, s.copy());
        }
    }

    /** 放置成功后把该货箱登记为放置者所属公司的「已接取订单」（库存使用装配前缓存） */
    private static void registerPlacedOrder(ServerLevel level, Player player, BlockPos pos,
                                            java.util.UUID subLevelUuid,
                                            net.minecraft.world.SimpleContainer invSnapshot,
                                            CargoData data) {
        try {
            if (player == null || data == null || !data.hasTarget()) return;
            String orderId = data.getOrderId();
            if (orderId == null || orderId.isEmpty()) return;
            java.util.UUID companyId = com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                    .getCompanyIdOfPlayer(player.getUUID());
            if (companyId == null) return; // 未加入公司：无订单列表可登记

            com.hzldm.createcargodispatch.cargo.StationType sourceType =
                    com.hzldm.createcargodispatch.cargo.StationType.byId(data.getSourceStationType());
            com.hzldm.createcargodispatch.cargo.StationType targetType =
                    com.hzldm.createcargodispatch.cargo.StationType.byId(data.getTargetStationType());

            // 物品与数量以装配前缓存的货箱库存为准
            String cargoItemId = null;
            int cargoCount = 0;
            for (int i = 0; i < invSnapshot.getContainerSize(); i++) {
                ItemStack s = invSnapshot.getItem(i);
                if (!s.isEmpty()) {
                    cargoCount += s.getCount();
                    if (cargoItemId == null) {
                        cargoItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(s.getItem()).toString();
                    }
                }
            }
            if (cargoItemId == null) {
                cargoItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(com.hzldm.createcargodispatch.cargo.StationType.getAllCargoPool().get(0))
                        .toString();
            }

            com.hzldm.createcargodispatch.cargo.OrderData order =
                    new com.hzldm.createcargodispatch.cargo.OrderData(
                            orderId, pos, level.dimension().location(),
                            data.getTargetPos(), data.getTargetDimension(),
                            com.hzldm.createcargodispatch.cargo.TransportType.LAND,
                            cargoItemId, cargoCount, 0, sourceType, targetType,
                            level.getGameTime());
            order.setAcceptedPlayer(player.getUUID());
            order.setOwnerCompany(companyId);
            order.setTargetStationId(data.getTargetStationId());
            order.setSubLevelUuid(subLevelUuid);
            if (!com.hzldm.createcargodispatch.cargo.OrderManager
                    .registerManualAcceptedOrder(order, level)) {
                return; // 同 id 订单已存在（重复放置/装配），幂等忽略
            }
            syncCompanyActiveOrders(level, companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoCreative] 登记放置货箱手工订单失败 pos={}", pos, t);
        }
    }

    /** 向公司所有在线成员重推活跃订单列表 */
    private static void syncCompanyActiveOrders(ServerLevel level, java.util.UUID companyId) {
        com.hzldm.createcargodispatch.company.CompanyStore store =
                com.hzldm.createcargodispatch.company.CompanyStore.get(level);
        for (net.minecraft.server.level.ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            if (!store.isMember(companyId, online.getUUID())) continue;
            java.util.List<com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload.ActiveOrderEntry> active =
                    com.hzldm.createcargodispatch.cargo.OrderManager
                            .getAcceptedOrdersByPlayer(level, online.getUUID()).stream()
                            .map(com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload.ActiveOrderEntry::from)
                            .toList();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    online, new com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload(active));
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        // 注意：Rotation 枚举没有 rotate(Axis) 重载，仅支持 rotate(Direction)
        // 对 HORIZONTAL_AXIS：90 度旋转（CLOCKWISE_90 / COUNTERCLOCKWISE_90）会使 X↔Z 交换
        // 0 度和 180 度旋转不会改变水平轴（轴无方向，X/Z 直线本身不变）
        Axis axis = state.getValue(HORIZONTAL_AXIS);
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            axis = (axis == Axis.X) ? Axis.Z : Axis.X;
        }
        return state.setValue(HORIZONTAL_AXIS, axis);
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        // Mirror 对水平 Axis 无影响：Axis 只区分 X/Z（无方向直线），镜像只翻转方向正负
        // 因此直接返回原值（若通过 Direction 转换 Axis，反而会造成不必要的 X↔Z 误切换）
        return state;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // 返回 ENTITYBLOCK_ANIMATED：方块默认模型不渲染，由 BlockEntityRenderer 渲染 CargoBoxModel
        // 原理：3x3x9 货箱模型整体在主方块位置渲染，其他 26 个方块不应显示默认方块模型
        //       使用 ENTITYBLOCK_ANIMATED 而非 INVISIBLE，让 Jade 正确显示方块名和图标
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    /**
     * 重写视觉形状（VISUAL shape）为空 → 货箱不阻挡第三人称相机视线
     *
     * <h3>为什么 noOcclusion 不够？</h3>
     * <p>{@code .noOcclusion()} 只告诉 Minecraft：这个方块"不会遮挡邻居面渲染 + 雨水可穿透"。
     * 但是第三人称相机距离计算（GameRenderer.pick → Camera → BlockGetter.clip）
     * 使用的是 {@link ShapeType#VISUAL}，对应的就是 {@code getVisualShape()}，而非
     * {@code getOcclusionShape()}。只有把 {@code getVisualShape} 返回 {@link Shapes#empty()}，
     * 相机 raycast 才会跳过货箱方块，不会把玩家视角卡在机械动力 contraption / Sable 坐垫
     * 等载具中心被货箱包围的位置。</p>
     *
     * <h3>为什么不改 getCollisionShape？</h3>
     * <p>否则玩家就可以穿货箱了，同时 Sable 的物理碰撞（质量、阻挡、结构装配）
     * 也依赖碰撞形状正常返回 1×1×1。保持碰撞形状不变，仅视觉形状为空。</p>
     */
    @Override
    public VoxelShape getVisualShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CargoBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return type == com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO.get()
                ? (lvl, pos, st, be) -> ((CargoBlockEntity) be).serverTick(lvl, pos)
                : null;
    }

    /**
     * 玩家破坏货箱方块时触发：
     *  1. 创造模式 → 先取消订单（BreakEvent 对 SubLevel 内部不触发，所以取消逻辑必须放这里）
     *  2. BFS 搜索所有相邻的 CargoBlock → 连锁移除
     *  3. 若此货箱属于 SubLevel 物理化货箱，顺便把整个 SubLevel 删除
     *  4. 最后注销 CargoManager 缓存（但**必须放在取消订单之后**，不然查不到 orderId）
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            // 第 0 步：在注销任何索引前，查出该方块所属订单并取消（仅创造模式能破坏货箱）。
            // 货箱被毁→货物丢失，订单不能放回待接单池，直接删除并通知接单玩家 + 提示破坏者。
            String orderId = CargoManager.findOrderIdByAny(pos);
            if (orderId != null) {
                net.minecraft.network.chat.Component reason =
                        net.minecraft.network.chat.Component.translatable(
                                "create_cargo_dispatch.order.cancel_reason_cargo_broken", orderId);
                // 内部：移除 ACCEPTED/PENDING 订单、通知在线接单玩家、删路径点、落盘
                com.hzldm.createcargodispatch.cargo.OrderManager.cancelOrder(orderId, reason, serverLevel);
                // 提示破坏者本人（若不是接单玩家也能看到反馈）
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "create_cargo_dispatch.order.cargo_broken_broadcast",
                        orderId, player.getName()));
            }

            // 判断是否是 SubLevel 货箱：通过 breakPos 查 startPos → SubLevel UUID
            UUID subLevelUuid = findSubLevelByAnyBreakPos(pos);

            // ================================================================
            // 第 1 步：如果是 SubLevel 货箱（有 uuid）
            // ================================================================
            if (subLevelUuid != null) {
                // 附着型（货箱在载具 plot 内）：只删货箱方块并重置连接器，载具必须保留。
                // 直接返回，跳过后续 removeSubLevel/BFS——载具上可能还有其他货箱，BFS 会误删。
                if (CargoManager.isAttachedVehicleCargo(subLevelUuid)) {
                    CargoManager.removeAttachedCargoBlocks(serverLevel, subLevelUuid);
                    return super.playerWillDestroy(level, pos, state, player);
                }
                // 独立货箱：若 SubLevelScanner 有能力按 UUID 找到 subLevel 实例就整体删除
                Object subLevel = SubLevelScanner.findSubLevelByUuid(serverLevel, subLevelUuid);
                if (subLevel != null) {
                    // 删除 SubLevel（清理 SubLevel 内所有 CargoBlock）
                    SubLevelScanner.removeSubLevel(serverLevel, subLevel);
                }
                // 无论 removeSubLevel 成功与否，都清理 CargoManager 的 SubLevel 映射
                // （保证下次 BreakEvent 不会再处理这一批）
                CargoManager.unregisterSubLevel(subLevelUuid);
            }

            // ================================================================
            // 第 2 步：BFS 连锁移除 CargoBlock（静态货箱必需）
            // ================================================================
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(pos);
            visited.add(pos);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos next = cur.relative(dir);
                    if (!visited.contains(next) && level.getBlockState(next).getBlock() instanceof CargoBlock) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
            // 在连锁移除之前，先注销任何一个 CargoBlock 找到的静态货运数据
            for (BlockPos p : visited) {
                if (level.getBlockEntity(p) instanceof CargoBlockEntity) {
                    com.hzldm.createcargodispatch.cargo.CargoManager.unregister(p);
                    break;
                }
            }
            // 移除所有非当前 pos 的货箱方块（当前 pos 由玩家破坏流程处理）
            for (BlockPos p : visited) {
                if (p.equals(pos)) continue;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 通过被破坏的方块 pos 查找是否属于某个已注册的 SubLevel 货箱
     * 原理：尝试 3 种方法，任一能找到 UUID 即返回
     *  1. START_TO_SUBLEVEL_MAP.get(pos) → 主方块正好被破坏
     *  2. BLOCK_TO_MAIN_MAP.get(pos) → 从方块查主方块 → START_TO_SUBLEVEL_MAP
     *  3. 遍历 SUBLEVEL_BLOCKS_MAP → 当前 pos 在某个 SubLevel blocks 列表里
     */
    @Nullable
    private static UUID findSubLevelByAnyBreakPos(BlockPos pos) {
        // 1. 主方块直接命中
        UUID byStart = CargoManager.getSubLevelUuidByStartPos(pos);
        if (byStart != null) return byStart;
        // 2. 从方块 → 主方块 → SubLevel UUID
        BlockPos main = CargoManager.queryMainPos(pos);
        if (main != null) {
            UUID byMain = CargoManager.getSubLevelUuidByStartPos(main);
            if (byMain != null) return byMain;
        }
        // 3. 最后遍历（兜底，SubLevel 内部方块被破坏的情况）
        return CargoManager.findSubLevelUuidContainingBlock(pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.hasBlockEntity() && (state.getBlock() != newState.getBlock() || !newState.hasBlockEntity())) {
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CargoBlockEntity cargo) {
            cargo.onPlayerUse(player);
        }
        return InteractionResult.SUCCESS;
    }
}
