package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoInfoHelper;
import com.hzldm.createcargodispatch.cargo.CargoPhysicsHelper;
import com.hzldm.createcargodispatch.cargo.CargoStructureHelper;
import com.hzldm.createcargodispatch.cargo.ReadOnlyItemHandler;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.Nameable;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 货箱 BlockEntity
 *
 * 核心设计：
 *  - 单方块设计：每个货箱持有独立的 SimpleContainer，不与其他货箱共享
 *  - 装配进 SubLevel 后 BlockEntity 位置变为相对坐标，但 localInventory 仍跟随自身
 *
 * 护目镜集成（一份代码兼容两个模组）：
 *  - 实现 Create 的 IHaveGoggleInformation 接口 → 机械动力眼镜自动显示
 *  - Cyber Goggles 通过 Mixin 增强 Create 护目镜渲染，自动识别该接口 → 赛博护目镜也显示
 *
 * 注意：
 *  - 不实现 Container 接口，避免 Jade 内置 ItemStorageProvider 双层显示物品
 *  - 不注册 IItemHandler capability：保护货物不被漏斗/管道提取
 */
public class CargoBlockEntity extends BlockEntity implements IHaveGoggleInformation, Nameable {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CargoDispatch");

    /** 货运站类型：子类覆盖返回具体类型，用于渲染器选择对应模型 */
    public StationType getStationType() {
        return StationType.GENERIC;
    }

    /** 本地 inventory（每个货箱独立持有） */
    private SimpleContainer localInventory;

    /** 缓存的只读 IItemHandler */
    private transient ReadOnlyItemHandler readOnlyHandler;

    /** 货运数据 */
    private CargoData cargoData = new CargoData();

    /** 是否为主方块（3x3x9 结构的底部中心，由 BlockEntityRenderer 判断） */
    private boolean isMainBlock = false;

    /** 自定义名称（1.21.1 起 BlockEntity 不再持有通用名称字段，需自行实现 Nameable） */
    @Nullable
    private Component customName;

    public CargoBlockEntity(BlockPos pos, BlockState state) {
        this(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO.get(), pos, state);
    }

    /**
     * 子类构造器：传入对应 BlockEntityType
     * 原理：每种专属货箱 BlockEntity 绑定独立的 BlockEntityType，便于注册 capability 和识别
     */
    protected CargoBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.localInventory = createInventory();
    }

    /** 创建 inventory（格数以 CargoBalance 常量为单一真源） */
    private static SimpleContainer createInventory() {
        return new SimpleContainer(com.hzldm.createcargodispatch.cargo.CargoBalance.INVENTORY_SLOTS);
    }

    public void serverTick(Level level, BlockPos pos) {
        // 目前无需 tick 逻辑
    }

    /**
     * 获取本货箱的 inventory
     * 原理：单方块设计，直接返回 localInventory，不查询共享表
     */
    public SimpleContainer getInventory() {
        return localInventory;
    }

    /**
     * 获取只读 IItemHandler
     * 原理：包装 localInventory 为只读，阻止漏斗提取
     */
    public IItemHandler getReadOnlyItemHandler() {
        if (readOnlyHandler == null) {
            readOnlyHandler = new ReadOnlyItemHandler(new InvWrapper(localInventory));
        }
        return readOnlyHandler;
    }

    /**
     * 获取可写的 IItemHandler（仅内部使用，用于注入货物）
     */
    public IItemHandler getWritableItemHandler() {
        return new InvWrapper(localInventory);
    }

    /**
     * 计算货箱物品总质量（mass 单位；1.0 mass = 1 标准方块 = 1 kg）
     * 原理：
     *  - 方块类物品（有Sable方块质量映射）：单品直接用Sable质量（不除9）
     *  - 普通物品（铁锭/钻石等无方块映射）：兜底 = 1/9 kg ≈ 0.111 kg
     *  - 总质量 = Σ(单品质量 × stack.getCount())，可直接作为 kg 展示
     * @return 物品总质量（1.0 = 1 个标准方块 = 1 kg）
     */
    public double getWeight() {
        double totalMass = 0;
        for (int i = 0; i < localInventory.getContainerSize(); i++) {
            ItemStack stack = localInventory.getItem(i);
            if (!stack.isEmpty()) {
                double itemMass = CargoPhysicsHelper.getItemMass(stack);
                totalMass += itemMass * stack.getCount();
            }
        }
        return totalMass;
    }

    // ===== Create 护目镜 & 赛博护目镜 信息显示 =====
    // 原理：IHaveGoggleInformation.addToGoggleTooltip 由 Create 的
    //       GoggleOverlayRenderer 在客户端调用；
    //       Cyber Goggles 通过 Mixin 扩展同一渲染管线，
    //       因此在此处添加信息即同时兼容两款护目镜。
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean added = false;
        CargoData data = cargoData;

        // 订单 ID
        if (data != null && !data.getOrderId().isEmpty()) {
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.order_id", data.getOrderId()
            ).withStyle(ChatFormatting.YELLOW));
            added = true;
        }

        // 货运站类型
        StationType st = getStationType();
        if (st != StationType.GENERIC) {
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.station_type",
                    Component.translatable("create_cargo_dispatch.station_type." + st.getId())
            ).withStyle(ChatFormatting.AQUA));
            added = true;
        }

        // 获取有效 inventory（非主方块/客户端也能用，因为接单时已复制 inventory 到所有方块）
        SimpleContainer effectiveInv = resolveEffectiveInventory();

        // 货物重量（仅物品，kg）：基于有效 inventory 计算，避免非主方块时为 0
        double cargoWeight = (effectiveInv == localInventory)
                ? getWeight()
                : calculateInventoryWeight(effectiveInv);

        String orderId = data != null ? data.getOrderId() : "";
        if (!orderId.isEmpty()) {
            // ===== 已接单（SubLevel 物理货箱）：显示「货物重量」和「总重量」两行 =====
            // 总重量 = 81 块方块结构质量 + 物品质量（与 Sable 内部追踪一致）
            double totalWeight = calculateStructureMassKg() + cargoWeight;
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.cargo_weight",
                    String.format("%.2f", cargoWeight)
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.total_weight",
                    String.format("%.2f", totalWeight)
            ).withStyle(ChatFormatting.WHITE));
        } else {
            // ===== 未接单（静态货箱）：保持原一行「重量」（= 仅物品重量） =====
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.weight",
                    String.format("%.2f", cargoWeight)
            ).withStyle(ChatFormatting.GRAY));
        }
        added = true;

        // 货物内容：合并相同物品，显示总数量（不按 stack 拆分）
        List<ItemStack> merged = mergeInventoryItems(effectiveInv);
        if (!merged.isEmpty()) {
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.goggles.contents"
            ).withStyle(ChatFormatting.WHITE));
            int maxDisplay = isPlayerSneaking ? 27 : 8; // 不潜行最多显示前 8 类物品
            int displayed = 0;
            for (int i = 0; i < merged.size() && displayed < maxDisplay; i++) {
                ItemStack s = merged.get(i);
                tooltip.add(Component.literal(
                        "  " + s.getCount() + "x " + s.getHoverName().getString()
                ).withStyle(ChatFormatting.GRAY));
                displayed++;
            }
            if (merged.size() > displayed) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.goggles.more",
                        merged.size() - displayed
                ).withStyle(ChatFormatting.DARK_GRAY));
            }
            added = true;
        }

        // 目标坐标
        if (data != null) {
            BlockPos tp = data.getTargetPos();
            if (tp != null && !tp.equals(BlockPos.ZERO)) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.goggles.target",
                        tp.getX(), tp.getY(), tp.getZ()
                ).withStyle(ChatFormatting.GREEN));
                added = true;
            }
        }

        return added;
    }

    /** 计算指定 inventory 的物品总质量（mass 单位；1.0 = 1 标准方块 = 1 kg，见 getWeight） */
    public static double calculateInventoryWeight(SimpleContainer inv) {
        double total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                double m = CargoPhysicsHelper.getItemMass(stack);
                total += m * stack.getCount();
            }
        }
        return total;
    }

    /**
     * 计算整个货箱结构的空箱质量（用于总重量显示）。
     * 原理：所有结构方块为同种货箱方块，用 Sable 查单块质量 × 结构方块数（尺寸来自 cargoData）；
     * Sable 不可用时每块按 1kg 估算。
     */
    private double calculateStructureMassKg() {
        double single = CargoPhysicsHelper.getBlockStateMass(getBlockState());
        final int blockCount = (cargoData != null ? cargoData.getDimensions()
                : com.hzldm.createcargodispatch.cargo.CargoDimensions.DEFAULT).blockCount();
        if (single <= 0) return blockCount * 1.0; // 兜底：每块按 1kg
        return single * blockCount;
    }

    /**
     * 合并 inventory 中相同物品的数量（按 Item + Damage + Components 判等）
     * 返回合并后的 ItemStack 列表（每个物品只有一个 stack，count 是总数）
     */
    private static List<ItemStack> mergeInventoryItems(SimpleContainer inv) {
        java.util.LinkedHashMap<String, ItemStack> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            // 用 Item 的描述 ID 作为合并 key（同 ID 视为同一种物品）
            net.minecraft.resources.ResourceLocation itemId =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            String key = itemId.toString();
            ItemStack existing = map.get(key);
            if (existing == null) {
                map.put(key, stack.copy());
            } else {
                existing.grow(stack.getCount());
            }
        }
        return new ArrayList<>(map.values());
    }

    /** 计算有效 inventory：优先本地，非主方块服务端查 CargoManager 快照 */
    private SimpleContainer resolveEffectiveInventory() {
        // 修复：客户端场景直接用 localInventory（因为我们在接单时已复制 inventory 到所有 27 方块的 NBT）
        // 这样非主方块在客户端也有完整物品数据
        if (level == null || level.isClientSide || isMainBlock) {
            return localInventory;
        }
        // 服务端且非主方块：尝试通过 orderId 查快照
        String orderId = cargoData != null ? cargoData.getOrderId() : "";
        if (!orderId.isEmpty()) {
            java.util.List<ItemStack> snapshot =
                    com.hzldm.createcargodispatch.cargo.CargoManager.getInventorySnapshotByOrder(orderId);
            if (snapshot != null && !snapshot.isEmpty()) {
                return snapshotToContainer(snapshot);
            }
        }
        return localInventory;
    }

    /**
     * 玩家右键：查看货运信息
     *
     * 原理：
     *  - 装配前把 cargoData 写入所有 27 个方块，Sable moveBlocks 保留 NBT
     *  - 所有方块（包括非主方块）都有 cargoData
     *  - 主方块：直接使用自身 cargoData 和 inventory
     *  - 非主方块：使用自身 cargoData，inventory 通过 orderId 查询快照
     *    （不依赖坐标，避免 AssemblyTransform 变换问题）
     */
    public void onPlayerUse(Player player) {
        if (level == null || level.isClientSide) return;
        CargoData effectiveData = cargoData;
        SimpleContainer effectiveInv = localInventory;
        if (!isMainBlock) {
            // 非主方块：cargoData 已随 NBT 保留，但 inventory 为空
            // 通过 orderId 查询装配前缓存的 inventory 快照
            String orderId = cargoData != null ? cargoData.getOrderId() : "";
            if (!orderId.isEmpty()) {
                java.util.List<ItemStack> snapshot =
                        com.hzldm.createcargodispatch.cargo.CargoManager.getInventorySnapshotByOrder(orderId);
                if (snapshot != null && !snapshot.isEmpty()) {
                    effectiveInv = snapshotToContainer(snapshot);
                }
            }
        }
        if (effectiveData != null && effectiveData.hasTarget()) {
            CargoInfoHelper.sendInfoToPlayer(player, effectiveData, getBlockPos(), effectiveInv);
        } else {
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.cargo.info.no_data"));
        }
    }

    /**
     * 把物品快照转换为 SimpleContainer
     * 原理：CargoInfoHelper.sendInfoToPlayer 需要 SimpleContainer 参数
     */
    private SimpleContainer snapshotToContainer(java.util.List<ItemStack> snapshot) {
        SimpleContainer container = new SimpleContainer(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            container.setItem(i, snapshot.get(i).copy());
        }
        return container;
    }

    public CargoData getCargoData() {
        return cargoData;
    }

    public void setCargoData(CargoData data) {
        this.cargoData = data;
        setChanged();
    }

    /**
     * 标记为主方块
     * 原理：生成 3x3x9 多方块结构时，只有底部中心方块被标记为主方块
     *       BlockEntityRenderer 只在主方块渲染整个模型
     */
    public void setMainBlock(boolean main) {
        this.isMainBlock = main;
        setChanged();
    }

    public boolean isMainBlock() {
        return isMainBlock;
    }

    // ===== 自定义名称（1.21.1 Nameable 组件化） =====

    public void setCustomName(Component name) {
        this.customName = name;
        setChanged();
    }

    @Override
    public Component getName() {
        return customName != null
                ? customName
                : Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    @Nullable
    public Component getCustomName() {
        return customName;
    }

    /** 放置方块时从物品组件接收 CUSTOM_NAME（1.21.1 起替代旧版自动 NBT 名称） */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        Component name = input.get(DataComponents.CUSTOM_NAME);
        if (name != null) this.customName = name;
    }

    /** 掉落物/存盘组件收集，保证自定义名随物品往返 */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CUSTOM_NAME, customName);
    }

    /** CustomName 已由隐式组件提供，加载时从 NBT 中剔除避免重复应用 */
    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("CustomName");
    }

    // ===== NBT 保存/加载 =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", localInventory.createTag(registries));
        if (cargoData != null) {
            tag.put("CargoData", cargoData.save(registries));
        }
        tag.putBoolean("IsMainBlock", isMainBlock);
        if (customName != null) {
            tag.putString("CustomName", Component.Serializer.toJson(customName, registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            localInventory.fromTag(tag.getList("Inventory", 10), registries);
        }
        if (tag.contains("CargoData")) {
            cargoData.load(tag.getCompound("CargoData"), registries);
        }
        isMainBlock = tag.getBoolean("IsMainBlock");
        if (tag.contains("CustomName", 8)) {
            customName = BlockEntity.parseCustomNameSafe(tag.getString("CustomName"), registries);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 单方块设计：无需注册共享 inventory
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        // 发送 BlockEntity 更新包到客户端
        // 原理：sendBlockUpdated 触发此方法，返回包含 getUpdateTag 数据的包
        //       客户端收到后调用 onDataPacket 加载数据
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * 注册 capability
     *
     * 原理：
     *  - 不注册 IItemHandler capability
     *  - 货物由系统注入（injectItemsIntoCargo 直接调用 getWritableItemHandler，不通过 capability 查询）
     *  - 不注册 capability = 漏斗/管道无法提取 = 保护货物
     *  - 同时避免 Jade 内置 ItemStorageProvider 检测到 capability 重复显示物品清单
     *
     * 保留 getReadOnlyItemHandler/getWritableItemHandler 方法供内部使用
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 不注册 IItemHandler capability
        // 原因：
        //  1. 货物由系统注入，不需要漏斗输入
        //  2. 不注册 = 漏斗无法提取 = 保护货物（比注册只读 handler 更彻底）
        //  3. 避免 Jade 内置 ItemStorageProvider 检测 capability 显示物品，导致两层信息
    }
}
