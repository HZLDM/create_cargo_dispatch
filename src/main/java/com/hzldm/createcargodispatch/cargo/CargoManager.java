package com.hzldm.createcargodispatch.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 货物管理器：维护货箱多方块结构与货运数据、共享 inventory 的映射
 *
 * 原理：
 *  - 使用 ConcurrentHashMap 保证线程安全
 *  - 主方块位置作为 key，存储 CargoData + SimpleContainer
 *  - 接单时创建 CargoGroup，所有连通的货箱方块共享同一个 SimpleContainer
 *  - 任意一个货箱方块都能查询到整个结构的 inventory
 *  - 物理化装配后缓存 inventory 快照，避免访问 SubLevel 内部 Level
 */
public final class CargoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateCargoDispatch");

    /** 货运数据映射：主控制器位置 → 货运数据 */
    private static final ConcurrentHashMap<BlockPos, CargoData> CARGO_MAP = new ConcurrentHashMap<>();

    /** 共享 inventory 映射：主控制器位置 → SimpleContainer（所有连通方块共享） */
    private static final ConcurrentHashMap<BlockPos, SimpleContainer> SHARED_INVENTORIES = new ConcurrentHashMap<>();

    /** SubLevel UUID → 装配前的 startPos（用于关联物理化货箱） */
    private static final ConcurrentHashMap<UUID, BlockPos> SUBLEVEL_START_MAP = new ConcurrentHashMap<>();

    /** 反向映射：startPos → SubLevel UUID（用于通过货箱位置查找物理化 SubLevel） */
    private static final ConcurrentHashMap<BlockPos, UUID> START_TO_SUBLEVEL_MAP = new ConcurrentHashMap<>();

    /** 方块位置 → 主方块位置（用于非主方块查询同结构主方块的 cargoData） */
    private static final ConcurrentHashMap<BlockPos, BlockPos> BLOCK_TO_MAIN_MAP = new ConcurrentHashMap<>();

    /** SubLevel UUID → 货箱所有方块位置（用于在 SubLevel 内部查找 BlockEntity） */
    private static final ConcurrentHashMap<UUID, List<BlockPos>> SUBLEVEL_BLOCKS_MAP = new ConcurrentHashMap<>();

    /** SubLevel UUID → inventory 快照（装配前缓存，货箱只读，内容不变） */
    private static final ConcurrentHashMap<UUID, List<ItemStack>> SUBLEVEL_INVENTORY_SNAPSHOT = new ConcurrentHashMap<>();

    /** SubLevel UUID → cargoData（直接缓存，避免访问 SubLevel 内部） */
    private static final ConcurrentHashMap<UUID, CargoData> SUBLEVEL_CARGO_DATA = new ConcurrentHashMap<>();

    /** 订单ID → SubLevel UUID（通过 cargoData.orderId 反查 SubLevel，不依赖坐标） */
    private static final ConcurrentHashMap<String, UUID> ORDER_TO_SUBLEVEL_MAP = new ConcurrentHashMap<>();

    /** 订单ID → 货箱主方块位置（findStartPosByOrderId 的 O(1) 真源，注册时同步维护） */
    private static final ConcurrentHashMap<String, BlockPos> ORDER_MAIN_POS_MAP = new ConcurrentHashMap<>();

    /** SubLevel UUID → 物理质量（吨，装配时缓存，供 Jade 直接读取） */
    private static final ConcurrentHashMap<UUID, Double> SUBLEVEL_MASS_CACHE = new ConcurrentHashMap<>();

    /**
     * 有意删除白名单：标记后 SubLevelRemovalMixin 不再保护该 UUID 的 markRemoved。
     * 原理：收货/玩家破坏/拆卸/放弃订单等「模组主动删除」必须绕过物理化保护，
     *       而保护逻辑无法区分调用来源，故由删除方显式标记、删完立即清理。
     */
    private static final java.util.Set<UUID> INTENTIONAL_REMOVALS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 附着型货箱标记集合：key = 载具 SubLevel UUID。
     * 原理：连接器模式的货箱方块直接放入载具 plot，与载具同属一个 SubLevel（不独立物理化）。
     *       收货/取消时只能删除 plot 内货箱方块，绝不能按独立货箱把整个载具移除，
     *       该集合是两条删除路径的行为开关。其余查询映射（cargoData/blocks/start）复用 SubLevel 系列。
     */
    private static final java.util.Set<UUID> ATTACHED_VEHICLE_CARGO =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CargoManager() {
    }

    /**
     * 注册「直接生成在载具 plot 内」的附着型货箱。
     * 复用 SubLevel 全部查询映射（key 为载具 UUID），检测器扫描逻辑零改动即可识别；
     * 额外打附着标记，供删除路径区分。
     *
     * @param vehicleUuid 载具 SubLevel UUID
     * @param gridMain    货箱主方块在 plot grid 的坐标
     * @param gridBlocks  货箱 27 个 plot grid 坐标
     */
    public static void registerAttachedCargo(UUID vehicleUuid, BlockPos gridMain, List<BlockPos> gridBlocks,
                                              SimpleContainer inventory, CargoData cargoData) {
        registerSubLevel(vehicleUuid, gridMain, gridBlocks, inventory, cargoData);
        ATTACHED_VEHICLE_CARGO.add(vehicleUuid);
    }

    /** 该 SubLevel 是否为「载货载具」（货箱方块在其 plot 内） */
    public static boolean isAttachedVehicleCargo(UUID subLevelUuid) {
        return subLevelUuid != null && ATTACHED_VEHICLE_CARGO.contains(subLevelUuid);
    }

    /**
     * 删除载具 plot 内的附着货箱方块（保留载具本身）。
     * 原理：对 plot 所在 inner Level 调 setBlock，坐标落在 plot grid 区域会自动路由到该载具 chunk，
     *       Sable 的 block change 钩子同步收缩物理碰撞/质量/包围盒。
     *
     * @return 实际删除的方块数；非附着型/无数据返回 -1
     */
    public static int removeAttachedCargoBlocks(ServerLevel serverLevel, UUID vehicleUuid) {
        if (!isAttachedVehicleCargo(vehicleUuid)) return -1;
        List<BlockPos> gridBlocks = SUBLEVEL_BLOCKS_MAP.get(vehicleUuid);
        // 先取主方块位置：unregisterSubLevel 后该映射会消失，重置连接器需要它
        BlockPos basePos = SUBLEVEL_START_MAP.get(vehicleUuid);
        int removed = 0;
        if (gridBlocks != null) {
            for (BlockPos p : gridBlocks) {
                serverLevel.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
                removed++;
            }
        }
        ATTACHED_VEHICLE_CARGO.remove(vehicleUuid);
        unregisterSubLevel(vehicleUuid);
        // 关键：同步重置连接器 BE 自身状态，否则其 attached 残留会永久占用连接器、新订单无法生成
        CargoDetacher.resetConnectorState(serverLevel, vehicleUuid, basePos);
        LOGGER.info("[CargoDispatch] 已删除载具 plot 内附着货箱 {} 格，载具保留 UUID={}", removed, vehicleUuid);
        return removed;
    }

    /**
     * 「切割分离」语义：货箱已在世界中生成新独立 SubLevel，清理以载具 UUID 为 key
     * 的附着标记与全部查询映射。与 {@link #removeAttachedCargoBlocks} 的区别：
     * 不负责删方块（切割器已把方块搬走），只做索引层解关联；载具本身从不归我们注册，不受影响。
     */
    public static void detachAttachedCargo(UUID vehicleUuid) {
        if (vehicleUuid == null) return;
        ATTACHED_VEHICLE_CARGO.remove(vehicleUuid);
        unregisterSubLevel(vehicleUuid);
    }

    /** 标记某 SubLevel 即将被模组有意删除（mixin 放行其 markRemoved） */
    public static void markSubLevelRemoval(UUID subLevelUuid) {
        if (subLevelUuid != null) INTENTIONAL_REMOVALS.add(subLevelUuid);
    }

    /** 清理有意删除标记（删除流程结束必须调用，避免白名单泄漏） */
    public static void clearSubLevelRemoval(UUID subLevelUuid) {
        if (subLevelUuid != null) INTENTIONAL_REMOVALS.remove(subLevelUuid);
    }

    /** 该 SubLevel 是否处于「允许删除」状态（供 SubLevelRemovalMixin 查询） */
    public static boolean isSubLevelRemovalAllowed(UUID subLevelUuid) {
        return subLevelUuid != null && INTENTIONAL_REMOVALS.contains(subLevelUuid);
    }

    /**
     * 注册一个货物组的货运数据
     * 原理：接单时调用，所有连通货箱方块共享同一组数据
     */
    public static void register(BlockPos controllerPos, CargoData data) {
        BlockPos ip = controllerPos.immutable();
        CARGO_MAP.put(ip, data);
        if (data != null && !nullOrEmpty(data.getOrderId())) {
            ORDER_MAIN_POS_MAP.put(data.getOrderId(), ip);
        }
        LOGGER.debug("[CargoDispatch] 注册货物 @ {} 订单 {}", controllerPos, data.getOrderId());
    }

    /**
     * 注册 SubLevel 关联（物理化装配后调用）
     * 原理：
     *  - 记录 SubLevel UUID → startPos、blocks、inventory 快照、cargoData
     *  - 装配后方块移入 SubLevel 内部，外部无法直接访问
     *  - 接收器检测到 SubLevel 后，直接从快照读取数据，无需访问 SubLevel 内部 Level
     *  - 货箱是 ReadOnly 的，inventory 内容在装配后不会改变，快照安全
     *
     * @param subLevelUuid SubLevel 的 UUID
     * @param startPos     装配前主方块位置（用于关联 CargoData）
     * @param blocks       所有方块位置列表
     * @param inventory    共享 inventory（用于快照）
     * @param cargoData    货运数据
     */
    public static void registerSubLevel(UUID subLevelUuid, BlockPos startPos, List<BlockPos> blocks,
                                          SimpleContainer inventory, CargoData cargoData) {
        SUBLEVEL_START_MAP.put(subLevelUuid, startPos.immutable());
        START_TO_SUBLEVEL_MAP.put(startPos.immutable(), subLevelUuid);
        // P9 并发安全：用 CopyOnWriteArrayList。blocks/inventory 装配完成后读多写少（只会 clear 一次），COW 开销远低于迭代器并发安全
        SUBLEVEL_BLOCKS_MAP.put(subLevelUuid, new CopyOnWriteArrayList<>(blocks));
        SUBLEVEL_CARGO_DATA.put(subLevelUuid, cargoData);

        // 建立方块→主方块的反向映射，让非主方块也能查询到主方块数据
        for (BlockPos pos : blocks) {
            BLOCK_TO_MAIN_MAP.put(pos.immutable(), startPos.immutable());
        }

        // 缓存 inventory 快照（非空物品）
        List<ItemStack> snapshot = new CopyOnWriteArrayList<>();
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    snapshot.add(stack.copy());
                }
            }
        }
        SUBLEVEL_INVENTORY_SNAPSHOT.put(subLevelUuid, snapshot);
        // 建立 orderId → SubLevel UUID 映射
        if (cargoData != null && !cargoData.getOrderId().isEmpty()) {
            ORDER_TO_SUBLEVEL_MAP.put(cargoData.getOrderId(), subLevelUuid);
        }
        LOGGER.info("[CargoDispatch] 注册 SubLevel {} → startPos {}, orderId={}, 物品快照 {} 项, inventory={}",
                subLevelUuid, startPos, cargoData != null ? cargoData.getOrderId() : "null",
                snapshot.size(), inventory != null ? "非null" : "null");
        // 触发持久化保存
        markDirty();
    }

    /** 触发持久化保存（需要在 ServerLevel 上下文调用） */
    private static void markDirty() {
        // 通过遍历 START_TO_SUBLEVEL_MAP 找到任意 ServerLevel
        // 原理：SavedData 绑定到维度，主世界一定存在
        try {
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                net.minecraft.server.level.ServerLevel overworld = server.overworld();
                if (overworld != null) {
                    CargoSubLevelStore.markDirty(overworld);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[CargoDispatch] 触发 SubLevel 保存失败: {}", t.getMessage());
        }
    }

    /** 通过订单ID 查询 SubLevel UUID（不依赖坐标，避免 AssemblyTransform 变换问题） */
    public static UUID getSubLevelUuidByOrder(String orderId) {
        return ORDER_TO_SUBLEVEL_MAP.get(orderId);
    }

    /**
     * 通过订单ID 反查「货箱主方块位置」。
     * 原理：CARGO_MAP 的 key 就是货箱主方块位置（generateCargo 以 cargoPos 注册、创造模式放置以 pos 注册），
     *       用于 removeCargoBlocks 降级 BFS 时定位货箱，避免依赖「订单 startPos 被临时改写」这种脏状态。
     * @return 主方块位置；找不到返回 null
     */
    @org.jetbrains.annotations.Nullable
    public static BlockPos findStartPosByOrderId(String orderId) {
        if (orderId == null || orderId.isEmpty()) return null;
        BlockPos direct = ORDER_MAIN_POS_MAP.get(orderId);
        if (direct != null) return direct;
        // 兜底：未经 register 入口写入的历史数据，遍历一次保证不遗漏
        for (Map.Entry<BlockPos, CargoData> e : CARGO_MAP.entrySet()) {
            if (e.getValue() != null && orderId.equals(e.getValue().getOrderId())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 通过订单ID 查询 inventory 快照 */
    public static List<ItemStack> getInventorySnapshotByOrder(String orderId) {
        UUID uuid = ORDER_TO_SUBLEVEL_MAP.get(orderId);
        return uuid != null ? SUBLEVEL_INVENTORY_SNAPSHOT.get(uuid) : null;
    }

    /** 缓存 SubLevel 物理质量（装配时由 CargoPhysicsHelper 调用） */
    public static void cacheSubLevelMass(UUID subLevelUuid, double mass) {
        SUBLEVEL_MASS_CACHE.put(subLevelUuid, mass);
    }

    /** 通过订单ID 查询物理质量（吨） */
    public static double getMassByOrder(String orderId) {
        UUID uuid = ORDER_TO_SUBLEVEL_MAP.get(orderId);
        return uuid != null ? SUBLEVEL_MASS_CACHE.getOrDefault(uuid, -1.0) : -1.0;
    }

    // ===== 持久化支持 =====

    /**
     * 从 SavedData 恢复内存数据
     * 原理：服务器启动时由 CargoSubLevelStore.load 调用，重建所有映射
     */
    public static void restoreFromSave(UUID subLevelUuid, BlockPos startPos, List<BlockPos> blocks,
                                        CargoData cargoData, List<ItemStack> snapshot, double mass,
                                        boolean attached) {
        SUBLEVEL_START_MAP.put(subLevelUuid, startPos.immutable());
        START_TO_SUBLEVEL_MAP.put(startPos.immutable(), subLevelUuid);
        // P9 并发安全：CopyOnWriteArrayList
        SUBLEVEL_BLOCKS_MAP.put(subLevelUuid, new CopyOnWriteArrayList<>(blocks));
        SUBLEVEL_CARGO_DATA.put(subLevelUuid, cargoData);
        SUBLEVEL_INVENTORY_SNAPSHOT.put(subLevelUuid, new CopyOnWriteArrayList<>(snapshot));
        SUBLEVEL_MASS_CACHE.put(subLevelUuid, mass);
        // 建立方块→主方块的反向映射
        for (BlockPos pos : blocks) {
            BLOCK_TO_MAIN_MAP.put(pos.immutable(), startPos.immutable());
        }
        // 建立 orderId → SubLevel UUID 映射
        if (cargoData != null && !cargoData.getOrderId().isEmpty()) {
            ORDER_TO_SUBLEVEL_MAP.put(cargoData.getOrderId(), subLevelUuid);
        }
        // 同时恢复 CARGO_MAP（用于 CargoDetectorBlockEntity.handleStaticCargoDetected）
        CARGO_MAP.put(startPos.immutable(), cargoData);
        if (attached) {
            ATTACHED_VEHICLE_CARGO.add(subLevelUuid);
        }
        LOGGER.info("[CargoDispatch] 恢复 SubLevel {} → startPos {}, orderId={}, 快照 {} 项, 附着={}",
                subLevelUuid, startPos, cargoData != null ? cargoData.getOrderId() : "null",
                snapshot.size(), attached);
    }

    /**
     * 移除一条失效的附着记录（重进对账、连接器自检发现真实载具不存在时调用）。
     * 清掉附着标记，并注销该 UUID 关联的全部内存索引（start/blocks/cargoData/反向映射）。
     */
    public static void removeAttachedRecord(UUID vehicleUuid) {
        if (vehicleUuid == null) return;
        ATTACHED_VEHICLE_CARGO.remove(vehicleUuid);
        unregisterSubLevel(vehicleUuid);
    }

    /**
     * 序列化所有 SubLevel 数据用于持久化
     * 原理：服务器保存时由 CargoSubLevelStore.save 调用
     */
    public static ListTag saveForPersistence(HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (UUID uuid : SUBLEVEL_START_MAP.keySet()) {
            BlockPos startPos = SUBLEVEL_START_MAP.get(uuid);
            List<BlockPos> blocks = SUBLEVEL_BLOCKS_MAP.get(uuid);
            CargoData cargoData = SUBLEVEL_CARGO_DATA.get(uuid);
            List<ItemStack> snapshot = SUBLEVEL_INVENTORY_SNAPSHOT.get(uuid);
            Double mass = SUBLEVEL_MASS_CACHE.get(uuid);

            if (startPos == null) continue;

            CompoundTag entry = new CompoundTag();
            entry.putUUID("UUID", uuid);
            entry.putInt("StartX", startPos.getX());
            entry.putInt("StartY", startPos.getY());
            entry.putInt("StartZ", startPos.getZ());

            // blocks 列表
            ListTag blockList = new ListTag();
            if (blocks != null) {
                for (BlockPos b : blocks) {
                    CompoundTag bTag = new CompoundTag();
                    bTag.putInt("X", b.getX());
                    bTag.putInt("Y", b.getY());
                    bTag.putInt("Z", b.getZ());
                    blockList.add(bTag);
                }
            }
            entry.put("Blocks", blockList);

            // cargoData
            if (cargoData != null) {
                entry.put("CargoData", cargoData.save(registries));
            }

            // inventory 快照
            ListTag snapList = new ListTag();
            if (snapshot != null) {
                for (ItemStack stack : snapshot) {
                    if (!stack.isEmpty()) {
                        snapList.add(stack.save(registries));
                    }
                }
            }
            entry.put("Snapshot", snapList);

            // 质量
            if (mass != null) {
                entry.putDouble("Mass", mass);
            }

            // 附着标记（货箱在载具 plot 内）：重启恢复后删除路径据此只删方块不删载具
            entry.putBoolean("Attached", ATTACHED_VEHICLE_CARGO.contains(uuid));

            entries.add(entry);
        }
        return entries;
    }

    /** 标记 SubLevel 数据为已修改（触发保存） */
    public static void markDirty(net.minecraft.server.level.ServerLevel level) {
        CargoSubLevelStore.markDirty(level);
    }

    /** 通过 SubLevel UUID 查询 startPos */
    public static BlockPos getStartPosBySubLevel(UUID subLevelUuid) {
        return SUBLEVEL_START_MAP.get(subLevelUuid);
    }

    /** 通过 SubLevel UUID 查询方块位置列表 */
    public static List<BlockPos> getBlocksBySubLevel(UUID subLevelUuid) {
        return SUBLEVEL_BLOCKS_MAP.get(subLevelUuid);
    }

    /** 通过 SubLevel UUID 查询 cargoData */
    public static CargoData getCargoDataBySubLevel(UUID subLevelUuid) {
        return SUBLEVEL_CARGO_DATA.get(subLevelUuid);
    }

    /** 通过 SubLevel UUID 查询 inventory 快照 */
    public static List<ItemStack> getInventorySnapshot(UUID subLevelUuid) {
        return SUBLEVEL_INVENTORY_SNAPSHOT.get(subLevelUuid);
    }

    /** 注销 SubLevel 关联 */
    public static void unregisterSubLevel(UUID subLevelUuid) {
        INTENTIONAL_REMOVALS.remove(subLevelUuid); // 兜底清理删除白名单
        BlockPos startPos = SUBLEVEL_START_MAP.remove(subLevelUuid);
        List<BlockPos> blocks = SUBLEVEL_BLOCKS_MAP.remove(subLevelUuid);
        CargoData data = SUBLEVEL_CARGO_DATA.remove(subLevelUuid);
        SUBLEVEL_INVENTORY_SNAPSHOT.remove(subLevelUuid);
        SUBLEVEL_MASS_CACHE.remove(subLevelUuid);
        if (startPos != null) {
            START_TO_SUBLEVEL_MAP.remove(startPos);
            CargoData removedData = CARGO_MAP.remove(startPos);
            // 仅当订单映射仍指向本位置时清理，防止误删新货箱占用的映射
            if (removedData != null && !nullOrEmpty(removedData.getOrderId())) {
                ORDER_MAIN_POS_MAP.remove(removedData.getOrderId(), startPos);
            }
            SHARED_INVENTORIES.remove(startPos);
        }
        // 清理 orderId → SubLevel UUID 映射
        if (data != null && !data.getOrderId().isEmpty()) {
            ORDER_TO_SUBLEVEL_MAP.remove(data.getOrderId());
        }
        // 清理方块→主方块映射
        if (blocks != null) {
            for (BlockPos pos : blocks) {
                BLOCK_TO_MAIN_MAP.remove(pos.immutable());
            }
        }
        // 触发持久化保存
        markDirty();
    }

    /** 通过 startPos 查询 SubLevel UUID（用于放弃订单时查找物理化货物） */
    public static UUID getSubLevelUuidByStartPos(BlockPos startPos) {
        return START_TO_SUBLEVEL_MAP.get(startPos.immutable());
    }

    /**
     * 手动绑定从方块 → 主方块的映射（setPlacedBy/黏着器 放置多方块结构时调用）
     * 让非主方块的 Jade / 护目镜 / 右键交互也能正确查到主方块 inventory 和 CargoData。
     * 线程安全：ConcurrentHashMap.putIfAbsent，避免重复写入覆盖已有（SubLevel 路径的更旧值）。
     */
    public static void bindSlaveToMain(BlockPos slavePos, BlockPos mainPos) {
        BLOCK_TO_MAIN_MAP.putIfAbsent(slavePos.immutable(), mainPos.immutable());
    }

    /**
     * 注册共享 inventory
     * 原理：主方块加载时调用，让从方块能查询到共享 inventory
     *
     * @param controllerPos 主方块位置
     * @param inventory    主方块持有的 SimpleContainer
     */
    public static void registerSharedInventory(BlockPos controllerPos, SimpleContainer inventory) {
        SHARED_INVENTORIES.put(controllerPos.immutable(), inventory);
    }

    /**
     * 查询共享 inventory
     * 原理：从方块通过自己的 controllerPos 查询主方块的共享 inventory
     *
     * @param controllerPos 主方块位置
     * @return 共享的 SimpleContainer，未注册返回 null
     */
    public static SimpleContainer getSharedInventory(BlockPos controllerPos) {
        return SHARED_INVENTORIES.get(controllerPos.immutable());
    }

    /**
     * 注销共享 inventory（货箱被破坏时调用）
     */
    public static void unregisterSharedInventory(BlockPos controllerPos) {
        SHARED_INVENTORIES.remove(controllerPos.immutable());
    }

    /** 注销货运数据 */
    public static void unregister(BlockPos controllerPos) {
        BlockPos ip = controllerPos.immutable();
        CargoData data = CARGO_MAP.remove(ip);
        if (data != null && !nullOrEmpty(data.getOrderId())) {
            ORDER_MAIN_POS_MAP.remove(data.getOrderId(), ip);
        }
        SHARED_INVENTORIES.remove(ip);
    }

    /**
     * 融合专用：注销静态货箱结构的<b>全部</b>索引。
     * 与 {@link #unregister} 的区别：同时清理 BLOCK_TO_MAIN_MAP 中每个从方块的条目，
     * 避免方块搬入载具 plot 后留下指向已失效主方块的孤儿映射。
     *
     * @param mainPos   主方块位置
     * @param allBlocks 结构全部方块（主+从）
     */
    public static void unregisterStaticStructure(BlockPos mainPos, List<BlockPos> allBlocks) {
        if (mainPos == null) return;
        BlockPos ip = mainPos.immutable();
        CargoData data = CARGO_MAP.remove(ip);
        if (data != null && !nullOrEmpty(data.getOrderId())) {
            ORDER_MAIN_POS_MAP.remove(data.getOrderId(), ip);
        }
        SHARED_INVENTORIES.remove(ip);
        if (allBlocks != null) {
            for (BlockPos p : allBlocks) {
                BLOCK_TO_MAIN_MAP.remove(p.immutable());
            }
        }
    }

    /**
     * 检查是否有已注册的货箱 SubLevel
     * 原理：用于 Mixin 快速判断是否需要检查 SubLevel 移除
     */
    public static boolean hasRegisteredSubLevels() {
        return !SUBLEVEL_START_MAP.isEmpty();
    }

    /**
     * 非主方块查询同结构主方块的 cargoData
     * 原理：通过 BLOCK_TO_MAIN_MAP 找到主方块位置，再从 CARGO_MAP 读取数据
     */
    public static CargoData queryMainCargoData(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockPos mainPos = BLOCK_TO_MAIN_MAP.get(pos.immutable());
        if (mainPos == null) return null;
        return CARGO_MAP.get(mainPos);
    }

    /**
     * 非主方块查询同结构主方块的 inventory
     * 原理：从 SubLevel 内部主方块 BlockEntity 读取 inventory
     */
    public static net.minecraft.world.SimpleContainer queryMainInventory(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockPos mainPos = BLOCK_TO_MAIN_MAP.get(pos.immutable());
        if (mainPos == null) return null;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(mainPos);
        if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoBlockEntity cargoBE) {
            return cargoBE.getInventory();
        }
        return null;
    }

    /**
     * 检查指定 UUID 是否为已注册的货箱 SubLevel
     * 原理：用于 Mixin 拦截 SubLevel.markRemoved()，阻止货箱被取消物理化
     */
    public static boolean isCargoSubLevel(UUID uuid) {
        return SUBLEVEL_START_MAP.containsKey(uuid);
    }

    /** 查询指定位置是否为货物，返回 CargoData（可能为 null） */
    public static CargoData query(BlockPos pos) {
        return CARGO_MAP.get(pos.immutable());
    }

    /** 查询指定位置是否为已注册的货物 */
    public static boolean isCargo(BlockPos pos) {
        return CARGO_MAP.containsKey(pos.immutable());
    }

    // =========================================================================
    // 货箱连接器：静态货箱（未物理化）扫描与枚举
    // =========================================================================

    /**
     * 在连接器附近扫描最近的「静态货箱主方块」（即 CARGO_MAP 中已注册但还没调用 assembleBlocks 的）
     *
     * @param center 连接器中心
     * @param rXZ    XZ 方向扫描半径（方块）
     * @param rY     Y 方向扫描半径（方块）
     * @return 最近的货箱主方块位置；null 表示没有
     */
    @org.jetbrains.annotations.Nullable
    public static BlockPos findNearestStaticCargoMain(BlockPos center, int rXZ, int rY) {
        if (center == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, CargoData> e : CARGO_MAP.entrySet()) {
            BlockPos mainPos = e.getKey();
            // 排除已经物理化的（START_TO_SUBLEVEL_MAP 中有就说明是 SubLevel 货箱，连接器另有扫描路径处理）
            if (START_TO_SUBLEVEL_MAP.containsKey(mainPos)) continue;
            int dx = Math.abs(mainPos.getX() - center.getX());
            int dy = Math.abs(mainPos.getY() - center.getY());
            int dz = Math.abs(mainPos.getZ() - center.getZ());
            if (dx > rXZ || dz > rXZ || dy > rY) continue;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < bestDist) {
                bestDist = d;
                best = mainPos;
            }
        }
        return best;
    }

    /**
     * 收集静态货箱（未物理化）主方块对应的「所有连通货箱方块位置」
     * 原理：从 BLOCK_TO_MAIN_MAP 反向枚举（value == mainPos → 加入列表），再把主方块自己加入头部。
     *
     * @param mainPos 主方块位置（来自 findNearestStaticCargoMain 或 CARGO_MAP keySet）
     * @return 所有货箱方块（主方块 + 从方块），至少包含 mainPos；不会返回 null
     */
    public static List<BlockPos> collectStaticCargoBlocks(BlockPos mainPos) {
        List<BlockPos> out = new ArrayList<>();
        if (mainPos == null) return out;
        out.add(mainPos.immutable());
        for (Map.Entry<BlockPos, BlockPos> e : BLOCK_TO_MAIN_MAP.entrySet()) {
            if (mainPos.equals(e.getValue())) {
                BlockPos slave = e.getKey();
                if (!mainPos.equals(slave)) out.add(slave);
            }
        }
        return out;
    }

    // =========================================================================
    // 用于"创造破坏货箱 → 取消订单的统一查询入口
    // =========================================================================

    /**
     * 通过任意位置查找对应订单ID（静态货箱 & SubLevel 货箱 兼容）
     *
     * 原理（搜索路径：
     *  1. 直接查 CARGO_MAP → 有订单ID就返回
     *  2. 查 BLOCK_TO_MAIN_MAP → 定位主方块 再查 CARGO_MAP
     *  3. 查 START_TO_SUBLEVEL_MAP → 通过 SubLevel UUID → SUBLEVEL_CARGO_DATA.orderId
     *  4. 查 SUBLEVEL_START_MAP.values 扫描 → 若传入 pos 是某个 SubLevel 的 start 或在其 blocks 中 → 返回对应 orderId
     *
     * 这样保证无论 breakPos 是哪个方块（主/从/Overworld/SubLevel 内部），
     * 只要属于任何注册的货物都能查得到订单ID。
     */
    @org.jetbrains.annotations.Nullable
    public static String findOrderIdByAny(BlockPos pos) {
        if (pos == null) return null;
        BlockPos ip = pos.immutable();
        // 1. 直接 CARGO_MAP 命中（静态货箱主方块 / SubLevel start 主方块）
        CargoData direct = CARGO_MAP.get(ip);
        if (direct != null && !nullOrEmpty(direct.getOrderId())) return direct.getOrderId();
        // 2. 从方块 → 主方块
        BlockPos main = BLOCK_TO_MAIN_MAP.get(ip);
        if (main != null) {
            CargoData mainData = CARGO_MAP.get(main);
            if (mainData != null && !nullOrEmpty(mainData.getOrderId())) return mainData.getOrderId();
            UUID subUuid = START_TO_SUBLEVEL_MAP.get(main);
            if (subUuid != null) {
                CargoData sd = SUBLEVEL_CARGO_DATA.get(subUuid);
                if (sd != null && !nullOrEmpty(sd.getOrderId())) return sd.getOrderId();
            }
        }
        // 3. START_TO_SUBLEVEL_MAP 直接命中（SubLevel startPos）
        UUID startUuid = START_TO_SUBLEVEL_MAP.get(ip);
        if (startUuid != null) {
            CargoData sd = SUBLEVEL_CARGO_DATA.get(startUuid);
            if (sd != null && !nullOrEmpty(sd.getOrderId())) return sd.getOrderId();
        }
        // 4. 遍历所有 SUBLEVEL，看 pos 是否在其 blocks 列表里（SubLevel 内部方块被破坏的情况）
        for (java.util.Map.Entry<UUID, List<BlockPos>> e : SUBLEVEL_BLOCKS_MAP.entrySet()) {
            List<BlockPos> list = e.getValue();
            if (list != null && list.contains(ip)) {
                CargoData sd = SUBLEVEL_CARGO_DATA.get(e.getKey());
                if (sd != null && !nullOrEmpty(sd.getOrderId())) return sd.getOrderId();
            }
        }
        return null;
    }

    /** 通过 SubLevel UUID 查订单ID */
    @org.jetbrains.annotations.Nullable
    public static String findOrderIdBySubLevelUuid(UUID uuid) {
        if (uuid == null) return null;
        CargoData sd = SUBLEVEL_CARGO_DATA.get(uuid);
        return sd != null ? sd.getOrderId() : null;
    }

    private static boolean nullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** 通过任意方块 pos 查主方块位置（非主方块也可查） */
    @org.jetbrains.annotations.Nullable
    public static BlockPos queryMainPos(BlockPos pos) {
        if (pos == null) return null;
        BlockPos direct = BLOCK_TO_MAIN_MAP.get(pos.immutable());
        if (direct != null) return direct;
        if (CARGO_MAP.containsKey(pos.immutable()) || START_TO_SUBLEVEL_MAP.containsKey(pos.immutable())) {
            return pos.immutable();
        }
        return null;
    }

    /**
     * 查找给定方块 pos 属于哪个 SubLevel（通过 SUBLEVEL_BLOCKS_MAP 遍历查找）
     * @return SubLevel UUID，不在任何已知货箱里则 null
     */
    @org.jetbrains.annotations.Nullable
    public static UUID findSubLevelUuidContainingBlock(BlockPos pos) {
        if (pos == null) return null;
        BlockPos ip = pos.immutable();
        for (java.util.Map.Entry<UUID, List<BlockPos>> e : SUBLEVEL_BLOCKS_MAP.entrySet()) {
            List<BlockPos> list = e.getValue();
            if (list != null && list.contains(ip)) return e.getKey();
        }
        return null;
    }

    /** 服务器停止时清理 */
    public static void clear() {
        CARGO_MAP.clear();
        SHARED_INVENTORIES.clear();
        SUBLEVEL_START_MAP.clear();
        START_TO_SUBLEVEL_MAP.clear();
        SUBLEVEL_BLOCKS_MAP.clear();
        SUBLEVEL_CARGO_DATA.clear();
        SUBLEVEL_INVENTORY_SNAPSHOT.clear();
        BLOCK_TO_MAIN_MAP.clear();
        ORDER_TO_SUBLEVEL_MAP.clear();
        ORDER_MAIN_POS_MAP.clear();
        SUBLEVEL_MASS_CACHE.clear();
    }
}
