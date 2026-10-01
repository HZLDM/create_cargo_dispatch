package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.blockentity.CargoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;

import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.hzldm.createcargodispatch.cargo.SableApiConstants.*;

/**
 * 货物物理化辅助类（Sable 软依赖，统一反射入口）
 *
 * <h3>修复记录：</h3>
 * <ul>
 *   <li><b>P4 修复</b>：disassembleSubLevelToWorld 中 plot 内部坐标 → 全局世界坐标 改为
 *       SubLevelScanner.plotInnerToGlobalWorld(logicalPose, innerPos)，支持 SubLevel 旋转/平移后的真实位置；
 *       同时加上 DISASSEMBLE_MAX_BLOCKS 限制防止 OOM。</li>
 *   <li><b>P5 修复</b>：applyCargoMass 改完 MergedMassTracker/selfTracker 的 mass / inverseMass 字段后，
 *       额外调用 SubLevelScanner.syncRigidBodyMass 尝试把新质量同步到底层 RigidBodyHandle，
 *       让物理引擎的加速度/碰撞响应真的受到物品质量影响，不再只是"纸面质量"。</li>
 *   <li><b>P7/P11/P12 修复</b>：初始化阶段核心 Sable 类缺失 → available=false 静默降级；
 *       其他核心反射（assembleBlocks 等）若缺失则 throw，避免后续 NPE。</li>
 *   <li><b>P16 修复</b>：类名/方法名统一走 {@link SableApiConstants}。</li>
 *   <li><b>P13 修复</b>：disassembleSubLevelToWorld 扫描超 {@link SubLevelScanner#DISASSEMBLE_MAX_BLOCKS}
 *       方块时直接拒绝，避免大 SubLevel OOM。</li>
 * </ul>
 */
public final class CargoPhysicsHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Physics");

    private static volatile boolean initialized = false;
    private static volatile boolean sableAvailable = false;
    private static MethodHandle assembleBlocksHandle;
    private static MethodHandle boundingBoxFromHandle;
    private static Method setNameMethod;
    private static Method getNameMethod;

    // MassTracker 字段缓存
    private static volatile boolean massFieldsResolved = false;
    private static Field mergedMassField;
    private static Field mergedInverseMassField;
    private static Field mergedSelfTrackerField;
    private static Field selfMassField;
    private static Field selfInverseMassField;

    // Sable 方块质量属性反射
    private static volatile boolean blockMassResolved = false;
    private static volatile boolean blockMassResolveAttempted = false;
    private static Object massPropertyType;
    private static Method sableGetPropertyMethod;

    // 9合1 压缩方块配方缓存（线程安全：ConcurrentHashMap + volatile dirty 双检）
    private static final ConcurrentHashMap<Item, Block> NINE_TO_ONE_BLOCK_MAP = new ConcurrentHashMap<>();
    private static volatile boolean nineToOneDirty = true;

    /** 查不到任何真实质量时的普通物品兜底值（标准方块 1kg ÷ 9 ≈ 0.111 kg） */
    private static final double UNKNOWN_ITEM_MASS = 1.0 / 9.0;
    /** Sable 对未单独配置的方块使用的默认质量（1.0 = 1kg / 1 标准方块） */
    private static final double SABLE_DEFAULT_BLOCK_MASS = 1.0;

    private CargoPhysicsHelper() {}

    // =========================================================================
    // 反射初始化
    // =========================================================================
    private static void init() {
        if (initialized) return;
        synchronized (CargoPhysicsHelper.class) {
            if (initialized) return;
            initialized = true;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Class<?> assemblyCls;
                Class<?> bbox3iCls;
                Class<?> bbox3icCls;
                Class<?> subLevelCls;
                try {
                    assemblyCls = Class.forName(CLASS_SUBLEVEL_ASSEMBLY, true, cl);
                    bbox3iCls   = Class.forName(CLASS_BOUNDING_BOX_3I,   true, cl);
                    bbox3icCls  = Class.forName(CLASS_BOUNDING_BOX_3IC,  true, cl);
                    subLevelCls = Class.forName(CLASS_SUBLEVEL,         true, cl);
                } catch (ClassNotFoundException noSable) {
                    LOGGER.warn("[CargoDispatch] Sable 未安装或物理 API 缺失，物理化功能全部降级。缺失类：{}", noSable.getMessage());
                    sableAvailable = false;
                    return;
                }

                MethodHandles.Lookup lookup = MethodHandles.lookup();

                // BoundingBox3i.from(Iterable)
                Method fromMethod = findFirst(bbox3iCls, METHOD_BB_FROM,
                        m -> m.getParameterCount() == 1 && Iterable.class.isAssignableFrom(m.getParameterTypes()[0]));
                if (fromMethod == null) throw new NoSuchMethodException(CLASS_BOUNDING_BOX_3I + "." + METHOD_BB_FROM + "(Iterable)");
                fromMethod.setAccessible(true);
                boundingBoxFromHandle = lookup.unreflect(fromMethod);

                // SubLevelAssemblyHelper.assembleBlocks(ServerLevel, BlockPos, Iterable, BoundingBox3ic)
                Method asm = findFirst(assemblyCls, METHOD_ASSEMBLE_BLOCKS, m -> {
                    Class<?>[] p = m.getParameterTypes();
                    return p.length == 4
                            && p[0] == ServerLevel.class
                            && p[1] == BlockPos.class
                            && Iterable.class.isAssignableFrom(p[2])
                            && p[3].isAssignableFrom(bbox3icCls);
                });
                if (asm == null) throw new NoSuchMethodException(CLASS_SUBLEVEL_ASSEMBLY + "." + METHOD_ASSEMBLE_BLOCKS);
                asm.setAccessible(true);
                assembleBlocksHandle = lookup.unreflect(asm);

                // SubLevel.setName / getName
                try {
                    setNameMethod = subLevelCls.getMethod(METHOD_SET_NAME, String.class);
                    setNameMethod.setAccessible(true);
                    getNameMethod = subLevelCls.getMethod(METHOD_GET_NAME);
                    getNameMethod.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    LOGGER.warn("[CargoDispatch] SubLevel.setName/getName 未找到：{}", e.getMessage());
                }

                ensureBlockMassResolved();

                sableAvailable = true;
                LOGGER.info("[CargoDispatch] Sable 物理装配 API 加载成功（assembly={}）", asm.getName());
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] CargoPhysicsHelper 初始化失败：{}: {}", t.getClass().getSimpleName(), t.getMessage(), t);
                sableAvailable = false;
            }
        }
    }

    private static Method findFirst(Class<?> cls, String name, java.util.function.Predicate<Method> filter) {
        for (Method m : cls.getDeclaredMethods()) {
            if (name.equals(m.getName()) && filter.test(m)) return m;
        }
        for (Method m : cls.getMethods()) {
            if (name.equals(m.getName()) && filter.test(m)) return m;
        }
        return null;
    }

    public static boolean isAvailable() { init(); return sableAvailable; }

    // =========================================================================
    // 装配 SubLevel
    // =========================================================================
    public static Object assembleBlocks(ServerLevel level, BlockPos anchor, Collection<BlockPos> blocks, String name) {
        init();
        if (!sableAvailable || assembleBlocksHandle == null) {
            LOGGER.warn("[CargoDispatch] Sable 不可用，跳过物理化装配 anchor={}", anchor);
            return null;
        }
        try {
            double itemWeight = readCargoItemWeight(level, anchor);
            CargoBlockEntity originalBE = (level.getBlockEntity(anchor) instanceof CargoBlockEntity cb) ? cb : null;
            // 必须在 invoke 之前缓存：装配后锚点 BE 被搬入 SubLevel，主世界原对象可能已脱离/失效
            SimpleContainer srcInv = originalBE != null ? originalBE.getInventory() : null;
            CargoData srcCargo = originalBE != null ? originalBE.getCargoData() : null;
            net.minecraft.network.chat.Component srcName = originalBE != null ? originalBE.getCustomName() : null;

            Object bounds = boundingBoxFromHandle.invoke(blocks);
            Object subLevel = assembleBlocksHandle.invoke(level, anchor, blocks, bounds);

            if (subLevel != null && name != null && !name.isEmpty() && setNameMethod != null) {
                try { setNameMethod.invoke(subLevel, name); }
                catch (Throwable t) { LOGGER.warn("[CargoDispatch] SubLevel.setName 失败：{}", t.getMessage()); }
            }
            if (subLevel != null) {
                restoreCargoDataToSubLevel(subLevel, srcInv, srcCargo, srcName);
            }
            if (itemWeight > 0.0 && subLevel != null) {
                applyCargoMass(level, subLevel, itemWeight, anchor);
            }
            LOGGER.debug("[CargoDispatch] 装配成功 anchor={} blocks={} name={}", anchor, blocks.size(), name);
            return subLevel;
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 物理化装配失败 anchor=" + anchor, t);
            return null;
        }
    }

    /**
     * 装配后兜底恢复：SubLevel 内部坐标经 AssemblyTransform 变换（通常按 plot 原点归一化），
     * 原世界坐标会全部 miss（旧实现恢复 count=0），因此按 plot bbox 遍历内部虚拟世界，
     * 把货运数据/物品/名称补写到每个货箱 BE。isMainBlock 不在此处理——它在装配前已写入并随 NBT 保留。
     */
    private static void restoreCargoDataToSubLevel(Object subLevel,
                                                   SimpleContainer srcInv,
                                                   CargoData srcCargo,
                                                   net.minecraft.network.chat.Component srcName) {
        if (srcCargo == null && srcInv == null && srcName == null) return;
        try {
            Level inner = SubLevelScanner.getSubLevelInternalLevel(subLevel);
            Object plot = SubLevelScanner.getPlot(subLevel);
            if (inner == null || plot == null) return;
            Object bb = SubLevelScanner.getPlotBBox(plot);
            if (bb == null) return;
            int[] m = SubLevelScanner.plotBBoxMinMax(bb);
            int restored = 0;
            for (int x = m[0]; x <= m[3]; x++) {
                for (int y = m[1]; y <= m[4]; y++) {
                    for (int z = m[2]; z <= m[5]; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (inner.getBlockEntity(pos) instanceof CargoBlockEntity cbe) {
                            if (srcCargo != null) cbe.setCargoData(srcCargo);
                            if (srcName != null) cbe.setCustomName(srcName);
                            if (srcInv != null) copyInventory(srcInv, cbe.getInventory());
                            cbe.setChanged();
                            BlockState bs = inner.getBlockState(pos);
                            inner.sendBlockUpdated(pos, bs, bs, Block.UPDATE_CLIENTS);
                            restored++;
                        }
                    }
                }
            }
            LOGGER.info("[CargoDispatch] SubLevel 兜底恢复 count={} data={} inv={} name={}",
                    restored, srcCargo != null, srcInv != null, srcName != null);
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] restoreCargoDataToSubLevel 失败：{}", t.getMessage());
        }
    }

    /** 按槽位复制容器内容（深拷贝物品栈） */
    private static void copyInventory(SimpleContainer src, SimpleContainer dst) {
        dst.clearContent();
        int n = Math.min(src.getContainerSize(), dst.getContainerSize());
        for (int i = 0; i < n; i++) {
            ItemStack s = src.getItem(i);
            if (!s.isEmpty()) dst.setItem(i, s.copy());
        }
    }

    private static double readCargoItemWeight(ServerLevel level, BlockPos anchor) {
        BlockEntity be = level.getBlockEntity(anchor);
        if (!(be instanceof CargoBlockEntity cbe)) return 0;
        return cbe.getWeight();
    }

    // =========================================================================
    // P5 修复：applyCargoMass + syncRigidBodyMass（物理后端同步）
    // =========================================================================
    private static void applyCargoMass(ServerLevel sl, Object subLevel, double itemWeight, BlockPos anchor) {
        if (subLevel == null || itemWeight <= 0.0) return;
        try {
            Method getMassTracker = subLevel.getClass().getMethod(METHOD_GET_MASS_TRACKER);
            getMassTracker.setAccessible(true);
            Object mergedTracker = getMassTracker.invoke(subLevel);
            if (mergedTracker == null) return;
            resolveMassFields(mergedTracker);
            if (!massFieldsResolved) {
                LOGGER.warn("[CargoDispatch] MassTracker 字段未解析，跳过质量修正 anchor={}", anchor);
                return;
            }
            double currentMass = mergedMassField.getDouble(mergedTracker);
            double newMass = currentMass + itemWeight;
            double newInverseMass = 1.0 / newMass;
            mergedMassField.setDouble(mergedTracker, newMass);
            mergedInverseMassField.setDouble(mergedTracker, newInverseMass);

            Object selfTracker = mergedSelfTrackerField.get(mergedTracker);
            if (selfTracker != null) {
                double selfOld = selfMassField.getDouble(selfTracker);
                double selfNew = selfOld + itemWeight;
                selfMassField.setDouble(selfTracker, selfNew);
                selfInverseMassField.setDouble(selfTracker, 1.0 / selfNew);
            }

            // ★ P5 修复：把新质量同步到底层物理后端 RigidBodyHandle（如果 Sable 公开了 setMass）
            boolean synced = SubLevelScanner.syncRigidBodyMass(sl, subLevel, newMass);
            LOGGER.info("[CargoDispatch] 质量修正 anchor={}：{} → {}（+{}kg），rigidBodySync={}",
                    anchor, currentMass, newMass, itemWeight, synced ? "OK" : "skip(API未暴露)");

            // 缓存到 CargoManager（给 Jade / 其它上层 UI 用）
            try {
                Method getU = subLevel.getClass().getMethod(METHOD_GET_UNIQUE_ID);
                Object uo = getU.invoke(subLevel);
                if (uo instanceof UUID uuid) CargoManager.cacheSubLevelMass(uuid, newMass);
            } catch (Exception e) {
                LOGGER.warn("[CargoDispatch] 缓存 SubLevel 质量失败：{}", e.getMessage());
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] applyCargoMass 失败：{}", t.getMessage());
        }
    }

    /** applyCargoMass 的对外重载（路径 B 还原、平移等会用到，目前内部保留即可） */
    static void applyCargoMassDirect(ServerLevel sl, Object subLevel, double itemWeight, BlockPos anchor) {
        applyCargoMass(sl, subLevel, itemWeight, anchor);
    }

    private static void resolveMassFields(Object mergedTracker) {
        if (massFieldsResolved) return;
        synchronized (CargoPhysicsHelper.class) {
            if (massFieldsResolved) return;
            try {
                Class<?> mc = mergedTracker.getClass();
                mergedMassField        = findField(mc, "mass");
                mergedInverseMassField = findField(mc, "inverseMass");
                mergedSelfTrackerField = findField(mc, "selfTracker");
                if (mergedSelfTrackerField != null) {
                    Object self = mergedSelfTrackerField.get(mergedTracker);
                    if (self != null) {
                        Class<?> sc = self.getClass();
                        selfMassField        = findField(sc, "mass");
                        selfInverseMassField = findField(sc, "inverseMass");
                    }
                }
                massFieldsResolved = (mergedMassField != null && mergedInverseMassField != null
                        && selfMassField != null && selfInverseMassField != null);
                if (massFieldsResolved) LOGGER.debug("[CargoDispatch] MassTracker 字段解析成功");
                else LOGGER.warn("[CargoDispatch] MassTracker 字段解析不完整 merged(m={},i={}) self(m={},i={})",
                        mergedMassField != null, mergedInverseMassField != null,
                        selfMassField != null, selfInverseMassField != null);
            } catch (Throwable t) {
                LOGGER.warn("[CargoDispatch] resolveMassFields 失败：{}", t.getMessage());
            }
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
            } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    // =========================================================================
    // 方块质量 & 物品质量（反射 Sable PhysicsBlockPropertyTypes.MASS）
    // =========================================================================
    private static void ensureBlockMassResolved() {
        if (blockMassResolved) return;
        if (blockMassResolveAttempted) return;
        synchronized (CargoPhysicsHelper.class) {
            if (blockMassResolved) return;
            if (blockMassResolveAttempted) return;
            blockMassResolveAttempted = true;
            try {
                Class<?> propTypesCls = Class.forName(CLASS_BLOCK_PROPERTY_TYPES);
                Field massF = propTypesCls.getField("MASS"); massF.setAccessible(true);
                Object massRegistryObj = massF.get(null);

                Method getM = null;
                try {
                    Class<?> roCls = Class.forName("net.neoforged.neoforge.registries.RegistryObject");
                    getM = roCls.getMethod("get");
                } catch (Throwable t) {
                    for (Class<?> c = massRegistryObj.getClass(); c != Object.class && getM == null; c = c.getSuperclass()) {
                        try { getM = c.getDeclaredMethod("get"); }
                        catch (NoSuchMethodException ok) {
                            try { getM = c.getMethod("get"); } catch (NoSuchMethodException ignored) {}
                        }
                    }
                }
                if (getM == null) throw new ReflectiveOperationException("RegistryObject.get() 未找到");
                getM.setAccessible(true);
                massPropertyType = getM.invoke(massRegistryObj);

                Class<?> extCls = Class.forName(CLASS_BLOCK_STATE_EXT);
                Class<?> propTypeCls = Class.forName(CLASS_BLOCK_PROPERTY_TYPES + "$PhysicsBlockPropertyType");
                sableGetPropertyMethod = extCls.getMethod("sable$getProperty", propTypeCls);
                sableGetPropertyMethod.setAccessible(true);
                blockMassResolved = true;
                LOGGER.info("[CargoDispatch] Sable 方块质量反射初始化完成 massPropertyType={}",
                        massPropertyType == null ? "null" : massPropertyType.getClass().getName());
            } catch (Throwable t) {
                LOGGER.warn("[CargoDispatch] Sable 方块质量反射失败：{}: {}", t.getClass().getSimpleName(), t.getMessage());
                LOGGER.warn("[CargoDispatch] Sable 反射堆栈：", t);
            }
        }
    }

    public static double getBlockStateMass(BlockState state) {
        ensureBlockMassResolved();
        if (!blockMassResolved || massPropertyType == null || sableGetPropertyMethod == null) return -1;
        try {
            Object r = sableGetPropertyMethod.invoke(state, massPropertyType);
            if (r instanceof Double d) return d;
            return SABLE_DEFAULT_BLOCK_MASS;
        } catch (Throwable t) {
            if (state != Blocks.AIR.defaultBlockState()) {
                LOGGER.debug("[CargoDispatch] getBlockStateMass 异常 state={}：{}", state, t.getMessage());
            }
            return -1;
        }
    }

    public static double getItemMass(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        boolean trace = id.contains("netherite") || id.contains(":iron_ingot") || id.contains(":gold_ingot")
                || id.contains(":diamond") || id.contains(":emerald") || id.contains(":copper_ingot")
                || id.contains(":redstone") || id.contains(":lapis") || id.contains(":quartz") || id.contains(":coal");

        if (stack.getItem() instanceof BlockItem bi) {
            double bm = getBlockStateMass(bi.getBlock().defaultBlockState());
            if (bm < 0) bm = SABLE_DEFAULT_BLOCK_MASS;
            if (trace) LOGGER.info("[CargoDispatch-Debug] getItemMass id={} BlockItem block={} mass={}", id, bi.getBlock(), bm);
            if (bm > 0) return bm;
            double v = tryVsieItemMass(item); if (v > 0) return v;
            return UNKNOWN_ITEM_MASS;
        } else {
            double v = tryVsieItemMass(item);
            if (v > 0) return v;
            Block direct = Block.byItem(item);
            if (direct != null && direct != Blocks.AIR) {
                double bm = getBlockStateMass(direct.defaultBlockState());
                if (bm < 0) bm = SABLE_DEFAULT_BLOCK_MASS;
                if (trace) LOGGER.info("[CargoDispatch-Debug] getItemMass id={} Block.byItem={} mass={}", id, direct, bm);
                if (bm > 0) return bm;
            }
            Block compressed = getNineToOneBlock(item);
            if (compressed != null) {
                double bm = getBlockStateMass(compressed.defaultBlockState());
                if (bm < 0) bm = SABLE_DEFAULT_BLOCK_MASS;
                if (trace) LOGGER.info("[CargoDispatch-Debug] getItemMass id={} 9合1方块={} 单品质量≈{}",
                        id, compressed, String.format("%.4f", bm / 9.0));
                if (bm > 0) return bm / 9.0;
            }
            if (trace) LOGGER.info("[CargoDispatch-Debug] getItemMass id={} 兜底 UNKNOWN_ITEM_MASS≈{}",
                    id, String.format("%.4f", UNKNOWN_ITEM_MASS));
            return UNKNOWN_ITEM_MASS;
        }
    }

    private static double tryVsieItemMass(Item item) {
        return -1; // 预留扩展位
    }

    // =========================================================================
    // P4 修复：disassembleSubLevelToWorld（使用 SubLevelScanner.plotInnerToGlobalWorld 进行 pose 变换）
    // =========================================================================
    @org.jetbrains.annotations.Nullable
    public static DisassemblyResult disassembleSubLevelToWorld(ServerLevel level, Object subLevel) {
        init();
        if (!sableAvailable || subLevel == null) return null;
        Level innerLevel = SubLevelScanner.getSubLevelInternalLevel(subLevel);
        if (innerLevel == null) return null;

        List<BlockPos> innerPoses = new ArrayList<>();
        List<BlockState> innerStates = new ArrayList<>();
        List<CompoundTag> innerTags = new ArrayList<>();
        BlockPos mainAnchor = null;

        try {
            Object plot = SubLevelScanner.getPlot(subLevel);
            if (plot == null) return null;
            Object bb = SubLevelScanner.getPlotBBox(plot);
            if (bb == null) return null;
            int[] m = SubLevelScanner.plotBBoxMinMax(bb);
            int minX = m[0], minY = m[1], minZ = m[2];
            int maxX = m[3], maxY = m[4], maxZ = m[5];
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                LOGGER.error("[CargoDispatch] disassemble: plot BoundingBox 非法 ({},{},{})-({},{},{})",
                        minX, minY, minZ, maxX, maxY, maxZ);
                return null;
            }

            // P13 防 OOM：粗略计算方块数上限
            long vol = (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
            if (vol > (long) SubLevelScanner.DISASSEMBLE_MAX_BLOCKS * 4) {
                LOGGER.error("[CargoDispatch] disassemble: plot 体积={} 超过允许范围，拒绝拆卸（防止 OOM）", vol);
                return null;
            }

            Object pose = SubLevelScanner.getLogicalPose(subLevel);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos inner = new BlockPos(x, y, z);
                        BlockState st = innerLevel.getBlockState(inner);
                        if (st.isAir()) continue;
                        BlockEntity be = innerLevel.getBlockEntity(inner);
                        CompoundTag tag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
                        innerPoses.add(inner);
                        innerStates.add(st);
                        innerTags.add(tag);
                        if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoBlockEntity cbe && cbe.isMainBlock()) {
                            mainAnchor = inner;
                        }
                    }
                }
            }
            if (innerPoses.isEmpty()) return null;
            if (mainAnchor == null) mainAnchor = innerPoses.get(0);

            // P13 再做一次精确块数阈值
            if (innerPoses.size() > SubLevelScanner.DISASSEMBLE_MAX_BLOCKS) {
                LOGGER.error("[CargoDispatch] disassemble: 非AIR方块数={} 超过上限={}，拒绝拆卸（防止 OOM）",
                        innerPoses.size(), SubLevelScanner.DISASSEMBLE_MAX_BLOCKS);
                return null;
            }

            // P4 核心：plot inner → 全局世界坐标 走 logicalPose.transformPosition
            List<BlockPos> globalPoses = new ArrayList<>(innerPoses.size());
            boolean fallbackIdentity = true;
            for (BlockPos inner : innerPoses) {
                BlockPos gp = SubLevelScanner.plotInnerToGlobalWorld(pose, inner);
                if (!gp.equals(inner)) fallbackIdentity = false;
                globalPoses.add(gp);
            }
            if (fallbackIdentity) {
                LOGGER.debug("[CargoDispatch] disassemble: SubLevel pose 为恒等，inner→global 未产生偏移");
            } else {
                LOGGER.info("[CargoDispatch] disassemble: SubLevel pose 已应用（含旋转/平移），成功变换 {} 个内坐标→全局坐标",
                        globalPoses.size());
            }

            // 写回世界（顺序：先写回世界方块，再删除 SubLevel，避免回写过程中 SubLevel 还在渲染）
            int UPDATE = Block.UPDATE_ALL_IMMEDIATE;
            for (int i = 0; i < innerPoses.size(); i++) {
                BlockPos gp = globalPoses.get(i);
                BlockState st = innerStates.get(i);
                CompoundTag tag = innerTags.get(i);
                level.removeBlockEntity(gp);
                if (!level.setBlock(gp, st, UPDATE)) {
                    LOGGER.warn("[CargoDispatch] disassemble 写回方块失败 pos={} state={}", gp, st);
                }
                if (tag != null) {
                    BlockEntity nbe = level.getBlockEntity(gp);
                    if (nbe != null) {
                        try { nbe.loadWithComponents(tag, level.registryAccess()); nbe.setChanged(); }
                        catch (Throwable t) { LOGGER.warn("[CargoDispatch] disassemble 恢复BlockEntity NBT失败 pos={}：{}", gp, t.getMessage()); }
                    }
                }
            }

            // 然后删除 SubLevel 并同步 CargoManager 反注册
            UUID removed = SubLevelScanner.getSubLevelUuid(subLevel);
            boolean removedOk = SubLevelScanner.removeSubLevel(level, subLevel);
            if (!removedOk) LOGGER.warn("[CargoDispatch] disassemble: SubLevel 删除失败（可能导致 plot 泄漏）uuid={}", removed);
            if (removed != null) CargoManager.unregisterSubLevel(removed);

            return new DisassemblyResult(globalPoses, innerStates, innerTags, mainAnchor);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 拆卸SubLevel失败", t);
            return null;
        }
    }

    /** 拆卸结果（方块全局位置、BlockState、BlockEntity NBT、主方块锚点） */
    public record DisassemblyResult(
            List<BlockPos> globalPoses,
            List<BlockState> states,
            List<CompoundTag> beTags,
            BlockPos anchor
    ) {}

    // =========================================================================
    // 9合1 配方缓存（懒重建）
    // =========================================================================
    public static Block getNineToOneBlock(Item item) {
        if (nineToOneDirty || NINE_TO_ONE_BLOCK_MAP.isEmpty()) {
            RecipeManagerAndAccess ctx = resolveCurrentRecipeManagerAndAccess();
            if (ctx != null) {
                rebuildNineToOneBlockMap(ctx.recipeManager(), ctx.registryAccess());
                nineToOneDirty = false;
            }
        }
        return NINE_TO_ONE_BLOCK_MAP.get(item);
    }

    private record RecipeManagerAndAccess(RecipeManager recipeManager,
                                          net.minecraft.core.RegistryAccess registryAccess) {}

    private static RecipeManagerAndAccess resolveCurrentRecipeManagerAndAccess() {
        // 路径1：NeoForge 服务端钩子——专用服务端与单机内置服务端都有效，
        //        修复原实现只反射客户端 Minecraft、专用服务端永远拿不到配方的问题
        try {
            net.minecraft.server.MinecraftServer server =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                return new RecipeManagerAndAccess(server.getRecipeManager(), server.registryAccess());
            }
        } catch (Throwable ignored) {}
        // 路径2：多人游戏客户端（物理运行在客户端线程时），走客户端同步的配方
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            if (mc == null) return null;
            java.lang.reflect.Method getServer = mcCls.getMethod("getSingleplayerServer");
            Object server = getServer.invoke(mc);
            if (server != null) {
                java.lang.reflect.Method getRm = server.getClass().getMethod("getRecipeManager");
                java.lang.reflect.Method getRa = server.getClass().getMethod("registryAccess");
                Object rm = getRm.invoke(server);
                Object ra = getRa.invoke(server);
                if (rm instanceof RecipeManager r && ra instanceof net.minecraft.core.RegistryAccess a) return new RecipeManagerAndAccess(r, a);
            }
            java.lang.reflect.Method getConn = mcCls.getMethod("getConnection");
            Object conn = getConn.invoke(mc);
            if (conn != null) {
                java.lang.reflect.Method getRm = conn.getClass().getMethod("getRecipeManager");
                Object rm = getRm.invoke(conn);
                java.lang.reflect.Method getRa = mcCls.getMethod("registryAccess");
                Object ra = getRa.invoke(mc);
                if (rm instanceof RecipeManager r && ra instanceof net.minecraft.core.RegistryAccess a) return new RecipeManagerAndAccess(r, a);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void rebuildNineToOneBlockMapIfDirty(RecipeManager recipeManager,
                                                       net.minecraft.core.RegistryAccess registryAccess) {
        if (nineToOneDirty || NINE_TO_ONE_BLOCK_MAP.isEmpty()) rebuildNineToOneBlockMap(recipeManager, registryAccess);
    }

    public static void markNineToOneDirty() { nineToOneDirty = true; }

    public static void rebuildNineToOneBlockMap(RecipeManager recipeManager,
                                                net.minecraft.core.RegistryAccess registryAccess) {
        if (recipeManager == null || registryAccess == null) return;
        NINE_TO_ONE_BLOCK_MAP.clear();
        int matched = 0;
        for (RecipeHolder<?> holder : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            var recipe = holder.value();
            ItemStack out;
            try {
                out = recipe.getResultItem(registryAccess);
            } catch (Throwable t) { continue; }
            if (out == null || out.isEmpty()) continue;
            if (!(out.getItem() instanceof BlockItem blockItem)) continue;
            Block outBlock = blockItem.getBlock();
            if (outBlock == null || outBlock == Blocks.AIR) continue;

            Item commonItem = null;
            int totalIngredientItems = 0;
            boolean match = false;

            if (recipe instanceof ShapedRecipe shaped) {
                int w = shaped.getWidth(), h = shaped.getHeight();
                if (w * h != 9) continue;
                List<Ingredient> ingredients = shaped.getIngredients();
                for (Ingredient ing : ingredients) {
                    ItemStack[] stacks = ing.getItems();
                    if (stacks == null || stacks.length == 0) { match = false; break; }
                    Item first = stacks[0].getItem();
                    // 判断 Ingredient 内部是否所有 Item 均一致（允许多个等价 Item，取第一个即可）
                    if (commonItem == null) commonItem = first;
                    else if (first != commonItem) { match = false; break; }
                    totalIngredientItems++;
                }
                match = (totalIngredientItems == 9);
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                List<Ingredient> ingredients = shapeless.getIngredients();
                if (ingredients.size() != 9) continue;
                for (Ingredient ing : ingredients) {
                    ItemStack[] stacks = ing.getItems();
                    if (stacks == null || stacks.length == 0) { match = false; break; }
                    Item first = stacks[0].getItem();
                    if (commonItem == null) commonItem = first;
                    else if (first != commonItem) { match = false; break; }
                    totalIngredientItems++;
                }
                match = (totalIngredientItems == 9);
            }
            if (match && commonItem != null && commonItem != Items.AIR) {
                NINE_TO_ONE_BLOCK_MAP.put(commonItem, outBlock);
                matched++;
            }
        }
        LOGGER.info("[CargoDispatch] 9合1配方缓存重建完成：{} 条映射（/reload 触发后下次懒重建会刷新）", matched);
    }
}
