package com.hzldm.createcargodispatch.integration.jade;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.util.List;

/**
 * Jade（玉）HUD 集成
 *
 * 【API 依据 - 来自官方示例 FurnaceProvider】
 *   参考：xiaoliziawa/Capability-1.21-NeoForge（DeepWiki）
 *   关键签名：
 *     appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config)
 *     appendServerData(CompoundTag data, BlockAccessor accessor)
 *     Provider 用 enum INSTANCE 单例模式
 *
 * 【软依赖安全】
 *   @WailaPlugin 只在 Jade 存在时由 Jade 的类路径扫描器通过 Class.forName 加载
 *   Jade 不存在时，没有任何主代码 import 或 new 本类 → JVM 永不解析 Jade API 符号
 */
@WailaPlugin(CreateCargoDispatch.MODID)
@SuppressWarnings("unused") // 通过 @WailaPlugin 反射发现
public class JadeProviders implements IWailaPlugin {

    /** 兼容手动注册入口（JadeIntegration.init 反射调用） */
    public static void register() { /* no-op：@WailaPlugin 已自动注册 */ }

    @Override
    public void register(IWailaCommonRegistration registration) {
        // 服务端：注册数据同步 Provider
        registration.registerBlockDataProvider(CargoBlockProvider.INSTANCE, CargoBlockEntity.class);
        registration.registerBlockDataProvider(StationProvider.INSTANCE,   CargoStationBlockEntity.class);
        registration.registerBlockDataProvider(DetectorProvider.INSTANCE,  CargoDetectorBlockEntity.class);
        registration.registerBlockDataProvider(GeneratorProvider.INSTANCE, CargoGeneratorBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // 客户端：注册 HUD 渲染 Provider（绑定基类，覆盖所有专属子类）
        registration.registerBlockComponent(CargoBlockProvider.INSTANCE,
                com.hzldm.createcargodispatch.block.CargoBlock.class);
        registration.registerBlockComponent(StationProvider.INSTANCE,
                com.hzldm.createcargodispatch.block.CargoStationBlock.class);
        registration.registerBlockComponent(DetectorProvider.INSTANCE,
                com.hzldm.createcargodispatch.block.CargoDetectorBlock.class);
        registration.registerBlockComponent(GeneratorProvider.INSTANCE,
                com.hzldm.createcargodispatch.block.CargoGeneratorBlock.class);
    }

    // =====================================================================
    // 工具
    // =====================================================================
    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    // =========================================================================
    // 货箱 HUD：订单ID、站类型、重量、目标坐标
    // =========================================================================
    public enum CargoBlockProvider implements IBlockComponentProvider,
            IServerDataProvider<BlockAccessor> {
        INSTANCE;

        static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
                CreateCargoDispatch.MODID, "cargo_block");

        @Override
        @OnlyIn(Dist.CLIENT)
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag d = accessor.getServerData();
            if (d == null) return;

            if (d.contains("OrderId")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.order_id", d.getString("OrderId")
                ).withStyle(ChatFormatting.YELLOW));
            }
            if (d.contains("StationType")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_type",
                        Component.translatable(
                                "create_cargo_dispatch.station_short." + d.getString("StationType"))
                ).withStyle(ChatFormatting.AQUA));
            }
            // 货物重量（kg）：所有场景都显示
            if (d.contains("CargoWeightKg")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.cargo_weight",
                        String.format("%.2f", d.getDouble("CargoWeightKg"))
                ).withStyle(ChatFormatting.GRAY));
            }
            // 总重量（kg）：仅 SubLevel（有订单）场景显示（服务端有此字段）
            if (d.contains("TotalWeightKg")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.total_weight",
                        String.format("%.2f", d.getDouble("TotalWeightKg"))
                ).withStyle(ChatFormatting.WHITE));
            }
            // 显示货箱内部物品列表（合并相同物品显示总数量）
            if (d.contains("Items")) {
                ListTag itemsNbt = d.getList("Items", Tag.TAG_COMPOUND);
                if (!itemsNbt.isEmpty()) {
                    HolderLookup.Provider registries = accessor.getLevel().registryAccess();
                    // 先把 NBT 解析成 ItemStack 列表，然后合并相同物品
                    List<ItemStack> merged = mergeItemNbtList(itemsNbt, registries);
                    if (!merged.isEmpty()) {
                        tooltip.add(Component.translatable(
                                "create_cargo_dispatch.jade.contents"
                        ).withStyle(ChatFormatting.WHITE));
                        final int MAX = 8; // Jade HUD 空间有限，最多显示前 8 类物品
                        int displayed = 0;
                        for (int i = 0; i < merged.size() && displayed < MAX; i++) {
                            ItemStack stack = merged.get(i);
                            tooltip.add(Component.literal(
                                    "  " + stack.getCount() + "x " + stack.getHoverName().getString()
                            ).withStyle(ChatFormatting.GRAY));
                            displayed++;
                        }
                        // 基于合并后的"物品种类数"提示还有更多
                        if (merged.size() > displayed) {
                            tooltip.add(Component.translatable(
                                    "create_cargo_dispatch.jade.more",
                                    merged.size() - displayed
                            ).withStyle(ChatFormatting.DARK_GRAY));
                        }
                    }
                }
            }
            if (d.contains("TargetX")) {
                tooltip.add(Component.translatable("create_cargo_dispatch.jade.target",
                        d.getInt("TargetX"), d.getInt("TargetY"), d.getInt("TargetZ")
                ).withStyle(ChatFormatting.GREEN));
            }
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof CargoBlockEntity be)) return;
            Level level = accessor.getLevel();
            CargoData cargo = be.getCargoData();

            String orderId = cargo.getOrderId();
            if (!empty(orderId)) tag.putString("OrderId", orderId);

            StationType st = be.getStationType();
            if (st != StationType.GENERIC) tag.putString("StationType", st.getId());

            // ===== 质量（kg）：分开「货物重量」和「总重量」（仅 SubLevel 场景有总重量）=====
            // 1. 先解析 Jade 专用 inventory（静态用本地，SubLevel 用快照）
            SimpleContainer jadeInv = resolveJadeInventory(be, orderId);
            // 2. 货物重量（仅物品）：所有场景都从 jadeInv 计算
            double cargoWeight = CargoBlockEntity.calculateInventoryWeight(jadeInv);
            tag.putDouble("CargoWeightKg", cargoWeight);
            // 3. 总重量（结构 + 物品）：仅 SubLevel（有订单）时用 Sable 精确缓存值
            if (!empty(orderId)) {
                double sublevelTotal = CargoManager.getMassByOrder(orderId);
                if (sublevelTotal > 0) {
                    tag.putDouble("TotalWeightKg", sublevelTotal);
                }
            }

            // 同步货箱内的物品列表（复用上面解析好的 jadeInv）
            if (jadeInv != null) {
                HolderLookup.Provider registries = level.registryAccess();
                ListTag itemsList = new ListTag();
                for (int i = 0; i < jadeInv.getContainerSize(); i++) {
                    ItemStack stack = jadeInv.getItem(i);
                    if (!stack.isEmpty()) {
                        itemsList.add(stack.save(registries));
                    }
                }
                if (!itemsList.isEmpty()) {
                    tag.put("Items", itemsList);
                }
            }

            BlockPos tp = cargo.getTargetPos();
            if (tp != null && !tp.equals(BlockPos.ZERO)) {
                tag.putInt("TargetX", tp.getX());
                tag.putInt("TargetY", tp.getY());
                tag.putInt("TargetZ", tp.getZ());
            }
        }

        /** 计算 Jade 用的有效 inventory（处理 SubLevel 中非主方块情况） */
        private static SimpleContainer resolveJadeInventory(CargoBlockEntity be, String orderId) {
            if (be.isMainBlock()) {
                return be.getInventory();
            }
            // 非主方块：通过 orderId 查询装配前的 inventory 快照
            if (!empty(orderId)) {
                List<ItemStack> snapshot = CargoManager.getInventorySnapshotByOrder(orderId);
                if (snapshot != null && !snapshot.isEmpty()) {
                    SimpleContainer c = new SimpleContainer(snapshot.size());
                    for (int i = 0; i < snapshot.size(); i++) {
                        c.setItem(i, snapshot.get(i).copy());
                    }
                    return c;
                }
            }
            // 降级：直接返回本地 inventory（可能为空）
            return be.getInventory();
        }

        /**
         * 客户端：合并 ListTag 中相同物品的数量，按 Item ID 去重
         * 返回合并后的 ItemStack 列表（每个物品只有一个条目，count = 累计总数）
         */
        @OnlyIn(Dist.CLIENT)
        private static List<ItemStack> mergeItemNbtList(ListTag itemsNbt, HolderLookup.Provider registries) {
            java.util.LinkedHashMap<String, ItemStack> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < itemsNbt.size(); i++) {
                ItemStack stack = ItemStack.parseOptional(registries, itemsNbt.getCompound(i));
                if (stack.isEmpty()) continue;
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
            return new java.util.ArrayList<>(map.values());
        }

        @Override
        public ResourceLocation getUid() { return UID; }
    }

    // =========================================================================
    // 货运站 HUD：站类型、编号、自动提交开关
    // =========================================================================
    public enum StationProvider implements IBlockComponentProvider,
            IServerDataProvider<BlockAccessor> {
        INSTANCE;

        static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
                CreateCargoDispatch.MODID, "cargo_station");

        @Override
        @OnlyIn(Dist.CLIENT)
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag d = accessor.getServerData();
            if (d == null) return;
            if (d.contains("StationType")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_type",
                        Component.translatable(
                                "create_cargo_dispatch.station_short." + d.getString("StationType"))
                ).withStyle(ChatFormatting.AQUA));
            }
            if (d.contains("StationId")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_id", d.getString("StationId")
                ).withStyle(ChatFormatting.GOLD));
            }
            if (d.contains("AutoSubmit")) {
                boolean on = d.getBoolean("AutoSubmit");
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.auto_submit",
                        Component.translatable(on
                                ? "create_cargo_dispatch.common.on"
                                : "create_cargo_dispatch.common.off")
                ).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof CargoStationBlockEntity be)) return;
            if (be.getStationType() != StationType.GENERIC) {
                tag.putString("StationType", be.getStationType().getId());
            }
            if (!empty(be.getStationId())) tag.putString("StationId", be.getStationId());
            tag.putBoolean("AutoSubmit", be.isAutoSubmit());
        }

        @Override
        public ResourceLocation getUid() { return UID; }
    }

    // =========================================================================
    // 检测器 HUD：检测状态、绑定订单
    // =========================================================================
    public enum DetectorProvider implements IBlockComponentProvider,
            IServerDataProvider<BlockAccessor> {
        INSTANCE;

        static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
                CreateCargoDispatch.MODID, "cargo_detector");

        @Override
        @OnlyIn(Dist.CLIENT)
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag d = accessor.getServerData();
            if (d == null) return;
            if (d.contains("StationType")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_type",
                        Component.translatable(
                                "create_cargo_dispatch.station_short." + d.getString("StationType"))
                ).withStyle(ChatFormatting.AQUA));
            }
            if (d.contains("StationId")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_id", d.getString("StationId")
                ).withStyle(ChatFormatting.GOLD));
            }
            boolean detecting = d.getBoolean("Detecting");
            tooltip.add(Component.translatable(
                    "create_cargo_dispatch.jade.detector." + (detecting ? "detected" : "idle")
            ).withStyle(detecting ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            if (detecting && d.contains("DetectedOrder")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.detector.order",
                        d.getString("DetectedOrder")
                ).withStyle(ChatFormatting.YELLOW));
            }
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof CargoDetectorBlockEntity be)) return;
            StationType st = be.getStationType();
            if (st != StationType.GENERIC) tag.putString("StationType", st.getId());
            if (!empty(be.getStationId())) tag.putString("StationId", be.getStationId());

            boolean detecting = false;
            String detectedOrder = null;

            String bound = be.getBoundOrderId();
            if (!empty(bound)) {
                detecting = true;
                detectedOrder = bound;
            }

            BlockPos last = be.getLastDetectedCargoPos();
            if (last != null) {
                detecting = true;
                if (detectedOrder == null) {
                    CargoData cd = CargoManager.query(last);
                    if (cd != null && !empty(cd.getOrderId())) {
                        detectedOrder = cd.getOrderId();
                    }
                }
            }

            tag.putBoolean("Detecting", detecting);
            if (detectedOrder != null) tag.putString("DetectedOrder", detectedOrder);
        }

        @Override
        public ResourceLocation getUid() { return UID; }
    }

    // =========================================================================
    // 生成器 HUD：站类型、编号、待接单订单数
    // =========================================================================
    public enum GeneratorProvider implements IBlockComponentProvider,
            IServerDataProvider<BlockAccessor> {
        INSTANCE;

        static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
                CreateCargoDispatch.MODID, "cargo_generator");

        @Override
        @OnlyIn(Dist.CLIENT)
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag d = accessor.getServerData();
            if (d == null) return;
            if (d.contains("StationType")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_type",
                        Component.translatable(
                                "create_cargo_dispatch.station_short." + d.getString("StationType"))
                ).withStyle(ChatFormatting.AQUA));
            }
            if (d.contains("StationId")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.station_id", d.getString("StationId")
                ).withStyle(ChatFormatting.GOLD));
            }
            if (d.contains("PendingCount")) {
                tooltip.add(Component.translatable(
                        "create_cargo_dispatch.jade.generator.pending",
                        d.getInt("PendingCount")
                ).withStyle(ChatFormatting.WHITE));
            }
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof CargoGeneratorBlockEntity be)) return;
            if (be.getStationType() != StationType.GENERIC) {
                tag.putString("StationType", be.getStationType().getId());
            }
            if (!empty(be.getStationId())) tag.putString("StationId", be.getStationId());
            int n = OrderManager.getPendingOrders(be.getStationType()).size();
            tag.putInt("PendingCount", n);
        }

        @Override
        public ResourceLocation getUid() { return UID; }
    }
}
