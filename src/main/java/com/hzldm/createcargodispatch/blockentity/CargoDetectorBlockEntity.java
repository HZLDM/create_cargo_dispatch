package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.block.CargoDetectorBlock;
import com.hzldm.createcargodispatch.cargo.CargoData;
import com.hzldm.createcargodispatch.cargo.CargoGeneratorRegistry;
import com.hzldm.createcargodispatch.cargo.CargoInfoHelper;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.OrderData;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.StationLocationStore;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.cargo.SubLevelScanner;
import com.hzldm.createcargodispatch.config.ModConfig;
import com.hzldm.createcargodispatch.network.RemoveWaypointPayload;
import com.hzldm.createcargodispatch.redstone.RedstonePulser;
import com.hzldm.createcargodispatch.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * 货物接收器 BlockEntity
 *
 * 原理：
 *  - 定期扫描附近的 CargoBlock 方块（范围可配置）
 *  - 扫描到任一 CargoBlock 即触发收货流程：
 *    1. 通过 BFS 找到主方块位置（有 controllerPos 的方块）
 *    2. 通过 CargoManager 查询货运数据
 *    3. 读取 inventory 内容用于计算奖励
 *    4. 删除整个货箱多方块结构
 *    5. 给予接单玩家奖励物品
 *    6. 完成/注销订单
 *    7. 播放完成音效
 */
public class CargoDetectorBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Receiver");

    /** 扫描间隔（tick），默认 40 tick ≈ 2 秒（货箱不需要太灵敏检测，0.5秒太频繁导致卡顿） */
    private static final long SCAN_INTERVAL = 40;
    /** autoSubmit 查询缓存有效期（tick，10 秒——原 5 秒；站的 autoSubmit 开关极少切换，再降 50% 缓存失效频率 → 每 10 秒 1 次集合遍历+对象分配，进一步节省 GC Survivor） */
    private static final long AUTO_SUBMIT_CACHE_TICKS = 200;

    private BlockPos lastDetectedCargoPos = null;
    private long lastScanTick = 0;

    /**
     * 扫描范围循环用的可变 BlockPos——直接复用同一个实例，
     * 避免 scanForCargoNoReceive / scanForCargo 每 2 秒各 new 一个 MutableBlockPos，
     * 几十上百个 detector 场景下显著减少短命对象分配（GC Survivor 友好）。
     */
    private final BlockPos.MutableBlockPos mutableScanCursor = new BlockPos.MutableBlockPos();

    /** 缓存的关联 Station 位置（null 表示当前未找到关联站） */
    @Nullable
    private BlockPos cachedBoundStationPos = null;
    /** 缓存的 autoSubmit 结果（仅与 cachedBoundStationPos 同步有效） */
    private boolean cachedAutoSubmit = false;
    /** 缓存写入时的 gameTick；超过 AUTO_SUBMIT_CACHE_TICKS 过期 */
    private long cachedAutoSubmitTick = -1L;

    /** 绑定的订单 ID */
    @Nullable
    private String boundOrderId;
    /** 接单玩家 UUID */
    @Nullable
    private UUID boundPlayerId;
    /** 货运站类型（由结构处理器或生成器传播） */
    private StationType stationType = StationType.GENERIC;
    /** 货运站编号 */
    private String stationId = "";
    /** 客户端是否显示检测范围（Shift+右键切换，持久化并随更新包同步） */
    private boolean showRange = false;
    /** 货物成功提交红石脉冲（只在最终提交成功时触发） */
    private final RedstonePulser pulser = new RedstonePulser();

    /** 子类传入不同 BlockEntityType */
    protected CargoDetectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public CargoDetectorBlockEntity(BlockPos pos, BlockState state) {
        this(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO_DETECTOR.get(), pos, state);
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
        // 注册检测器到运行时表（station 查找 detector / 失效缓存使用）
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.registerDetector(level, getBlockPos(), stationType);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            CargoGeneratorRegistry.unregisterDetector(level, getBlockPos(), stationType);
        }
        super.setRemoved();
    }

    public void serverTick(Level level, BlockPos pos) {
        // 红石脉冲倒计时必须先于扫描节流的 early return，否则熄灭会被延迟
        pulser.tick(level, pos, getBlockState(), CargoDetectorBlock.LIT);
        long gameTime = level.getGameTime();
        // 处理世界重载后 gameTime 重置的情况：如果 lastScanTick > gameTime，重置为 0
        // 原理：lastScanTick 不持久化，但保险起见处理边界情况
        if (lastScanTick > gameTime) {
            lastScanTick = 0;
        }
        if (gameTime - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = gameTime;

        // 查询该检测器绑定的 station 是否开启自动提交
        // 原理：station.autoSubmit=true → 扫描 + 立即自动收货
        //       station.autoSubmit=false（默认）→ 只扫描并记录 lastDetectedCargoPos，
        //       不自动收货，由玩家在提交页面手动提交
        if (isBoundStationAutoSubmit(level)) {
            scanForCargo(level, pos);
        } else {
            scanForCargoNoReceive(level, pos);
        }
    }

    /** 货物成功提交后触发 20 tick、强度 15 的红石脉冲 */
    public void emitRedstonePulse() {
        pulser.trigger(level, getBlockPos(), getBlockState(), CargoDetectorBlock.LIT);
    }

    /**
     * 查询绑定的货运站是否开启自动提交
     *
     * 原理（性能优化）：
     *  - 旧版每 10 tick 做 193×65×193 ≈ 242 万次 getBlockState 大循环，是卡顿的主要来源
     *  - 新版直接查 CargoGeneratorRegistry 运行时注册表（同类型 station 一般 1~5 个，O(1)~O(5)）
     *  - 候选过滤：先 stationType 匹配，再 stationId 匹配 → 距离最近兜底
     *  - 加 100 tick（5秒）缓存，避免无意义重复查询
     *  - 返回前校验：仅在 chunk 已加载时真正读 BlockEntity 的 autoSubmit（避免强制加载 chunk 卡死）
     */
    private boolean isBoundStationAutoSubmit(Level level) {
        long now = level.getGameTime();
        // 缓存命中（无论 cachedBoundStationPos 是否为 null，只要时间没到就复用上次结果）
        if (cachedAutoSubmitTick != -1L && (now - cachedAutoSubmitTick) < AUTO_SUBMIT_CACHE_TICKS) {
            return cachedAutoSubmit;
        }
        BlockPos center = getBlockPos();
        String myId = (stationId != null && !stationId.isEmpty()) ? stationId : null;

        // 1. 只查同类型候选站（类型严格隔离：GENERIC 接收器只服务 GENERIC 站）
        List<BlockPos> candidates = CargoGeneratorRegistry.getStationsByType(level, stationType);

        BlockPos matchedPos = null;
        boolean matchedAutoSubmit = false;
        double nearestDistSq = Double.MAX_VALUE;

        for (BlockPos stationPos : candidates) {
            // 先粗过滤：type 已通过 CargoGeneratorRegistry 分组保证，无需再判断
            // chunk 未加载的站：不强制 getBlockEntity，跳过（避免级联加载chunk卡死）
            if (!level.isLoaded(stationPos)) continue;
            // 绑定半径硬限制：超过 XZ20/Y8 的站（邻居站）一律不关联
            if (!com.hzldm.createcargodispatch.cargo.StationGroupHelper.isWithinBindRadius(center, stationPos)) continue;
            BlockEntity be = level.getBlockEntity(stationPos);
            if (!(be instanceof CargoStationBlockEntity stationBE)) continue;

            // stationId 匹配：双方任意一方为空 → 视为"未指定id，允许绑定"；都不为空则必须相等
            String sId = stationBE.getStationId();
            boolean idMatch = (myId == null)
                    || (sId == null || sId.isEmpty())
                    || myId.equals(sId);
            if (!idMatch) continue;

            // 找到 stationId 精确匹配的 → 立即采用（优先级高于"距离最近"）
            if (myId != null && sId != null && !sId.isEmpty() && myId.equals(sId)) {
                matchedPos = stationPos;
                matchedAutoSubmit = stationBE.isAutoSubmit();
                break;
            }

            // id 宽松匹配（至少一方为空）：取距离最近的
            double distSq = stationPos.distSqr(center);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                matchedPos = stationPos;
                matchedAutoSubmit = stationBE.isAutoSubmit();
            }
        }

        // 2. 兜底：Registry 中没找到（可能站还没加载完）→ 读 StationLocationStore 持久化表，
        //    仅取距离最近的且 chunk 已加载的那一个（防止"第一次运行时没关联成功就永远false"）
        if (matchedPos == null && level instanceof ServerLevel serverLevel) {
            double nearest2 = Double.MAX_VALUE;
            List<BlockPos> stored = StationLocationStore.get(serverLevel)
                    .getStationsVerified(serverLevel, stationType);
            for (BlockPos stationPos : stored) {
                if (!level.isLoaded(stationPos)) continue;
                // 绑定半径硬限制（与注册表路径一致）
                if (!com.hzldm.createcargodispatch.cargo.StationGroupHelper.isWithinBindRadius(center, stationPos)) continue;
                BlockEntity be = level.getBlockEntity(stationPos);
                if (!(be instanceof CargoStationBlockEntity stationBE)) continue;
                // 已加载且 BE 有效：id 宽松匹配
                String sId = stationBE.getStationId();
                boolean idMatch = (myId == null)
                        || (sId == null || sId.isEmpty())
                        || myId.equals(sId);
                if (!idMatch) continue;
                double distSq = stationPos.distSqr(center);
                if (distSq < nearest2) {
                    nearest2 = distSq;
                    matchedPos = stationPos;
                    matchedAutoSubmit = stationBE.isAutoSubmit();
                }
            }
        }

        // 写入缓存（无论 matchedPos 是否为 null，都缓存当前结果）
        this.cachedBoundStationPos = matchedPos;
        this.cachedAutoSubmit = matchedAutoSubmit;
        this.cachedAutoSubmitTick = now;
        return matchedAutoSubmit;
    }

    /** 强制让 autoSubmit 缓存下次失效（Station 切换开关 / 数据变化时由 Station 调用） */
    public void invalidateAutoSubmitCache() {
        this.cachedBoundStationPos = null;
        this.cachedAutoSubmit = false;
        this.cachedAutoSubmitTick = -1L;
    }

    /**
     * 只扫描 + 记录检测到的货箱，不自动收货（station 自动提交关闭时的路径）
     */
    private void scanForCargoNoReceive(Level level, BlockPos selfPos) {
        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        BlockPos scanCenter = ScanVolumes.centerAbove(selfPos);
        boolean anyFound = false;

        // 1. 优先扫描 SubLevel（物理化货箱）
        if (SubLevelScanner.isAvailable() && level instanceof ServerLevel serverLevel) {
            List<Object> subLevels = SubLevelScanner.getNearbySubLevels(serverLevel, scanCenter, rangeXZ, rangeY);
            for (Object subLevel : subLevels) {
                UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
                if (uuid == null) continue;
                CargoData cargoData = CargoManager.getCargoDataBySubLevel(uuid);
                List<BlockPos> blocks = CargoManager.getBlocksBySubLevel(uuid);
                // SubLevel 与静态统一：hasTargetLenient + isRouteMatch
                if (cargoData != null && hasTargetLenient(cargoData)
                        && blocks != null && !blocks.isEmpty() && isRouteMatch(cargoData)) {
                    BlockPos start = CargoManager.getStartPosBySubLevel(uuid);
                    if (start != null) {
                        lastDetectedCargoPos = start;
                        anyFound = true;
                        break;  // 记录任意一个即可
                    }
                }
            }
        }

        // 2. 降级扫描静态 CargoBlock：使用 resolveStaticCargo（多来源合并，防止漏检）
        if (!anyFound) {
            BlockPos.MutableBlockPos cursor = mutableScanCursor;  // 复用实例字段，避免每 2 秒 new 一个短命对象
            outer:
            for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
                for (int dy = -rangeY; dy <= rangeY; dy++) {
                    for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                        cursor.setWithOffset(scanCenter, dx, dy, dz);
                        if (!(level.getBlockState(cursor).getBlock() instanceof CargoBlock)) continue;
                        StaticCargoResolve res = resolveStaticCargo(level, cursor.immutable());
                        // 与 SubLevel 路径严格对称：hasTargetLenient + isRouteMatch 都要满足
                        // 否则会出现「检测器 lastDetectedCargoPos 缓存了无订单/目标的空货箱」
                        if (res == null || res.cargoData() == null
                                || !hasTargetLenient(res.cargoData())
                                || !isRouteMatch(res.cargoData())) continue;
                        lastDetectedCargoPos = res.mainPos();
                        anyFound = true;
                        break outer;
                    }
                }
            }
        }

        if (anyFound) setChanged();
    }

    /**
     * 扫描附近的货箱（autoSubmit = true 时调用）
     * 原理：
     *  - 优先扫描 Sable SubLevel（物理化货箱）
     *  - 降级扫描静态 CargoBlock 方块（未装配或 Sable 不可用时）
     *  - 先做 lenient hasTarget + isRouteMatch 再收货（避免乱收不属于本站的货箱）
     *  - 静态货箱路径使用 resolveStaticCargo 合并多来源 cargoData，解决"放在检测器旁边识别不到"
     *  - 找到任一货箱即触发收货逻辑
     */
    private void scanForCargo(Level level, BlockPos selfPos) {
        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        BlockPos scanCenter = ScanVolumes.centerAbove(selfPos);

        // 1. 优先扫描 SubLevel（物理化货箱）
        if (SubLevelScanner.isAvailable() && level instanceof ServerLevel serverLevel) {
            List<Object> subLevels = SubLevelScanner.getNearbySubLevels(serverLevel, scanCenter, rangeXZ, rangeY);
            for (Object subLevel : subLevels) {
                UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
                if (uuid == null) continue;
                List<BlockPos> blocks = CargoManager.getBlocksBySubLevel(uuid);
                CargoData cargoData = CargoManager.getCargoDataBySubLevel(uuid);
                if (blocks != null && !blocks.isEmpty() && cargoData != null
                        && hasTargetLenient(cargoData) && isRouteMatch(cargoData)) {
                    BlockPos startPos = CargoManager.getStartPosBySubLevel(uuid);
                    if (startPos != null) {
                        LOGGER.debug("[CargoDispatch] 接收器检测到物理化货箱 SubLevel {} startPos {}", uuid, startPos);
                        handleSubLevelCargoDetected(serverLevel, subLevel, uuid, startPos, blocks);
                        return;
                    }
                }
            }
        }

        // 2. 降级扫描静态 CargoBlock 方块（resolveStaticCargo 多来源合并）
        BlockPos.MutableBlockPos cursor = mutableScanCursor;  // 复用实例字段，避免每 2 秒 new MutableBlockPos
        for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
            for (int dy = -rangeY; dy <= rangeY; dy++) {
                for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                    cursor.setWithOffset(scanCenter, dx, dy, dz);
                    if (!(level.getBlockState(cursor).getBlock() instanceof CargoBlock)) continue;
                    StaticCargoResolve res = resolveStaticCargo(level, cursor.immutable());
                    if (res == null) continue;
                    BlockPos found = res.mainPos();
                    LOGGER.debug("[CargoDispatch] 接收器在 {} 检测到静态货箱 {} (resolveStaticCargo 命中)", selfPos, found);
                    lastDetectedCargoPos = found;
                    setChanged();
                    handleStaticCargoDetected(level, found);
                    return;
                }
            }
        }
    }

    /**
     * 处理检测到的物理化货箱（SubLevel）
     * 原理：
     *  1. 从 CargoManager 缓存读取 cargoData 和 inventory 快照
     *  2. 检查 cargoData.targetStationType 是否匹配本检测器的 stationType
     *  3. 从快照计算货物数量用于奖励
     *  4. 删除整个 SubLevel 并注销数据
     *  5. 走通用的订单完成流程（奖励/订单/路径点/音效）
     */
    private void handleSubLevelCargoDetected(ServerLevel serverLevel, Object subLevel,
                                              UUID subLevelUuid, BlockPos startPos,
                                              List<BlockPos> blocks) {
        // 1. 从缓存读取 cargoData
        CargoData cargoData = CargoManager.getCargoDataBySubLevel(subLevelUuid);
        if (cargoData == null) {
            LOGGER.warn("[CargoDispatch] SubLevel {} 无缓存的 cargoData", subLevelUuid);
            return;
        }

        // 2. 路线匹配：检测器的 stationType 必须与货物的 targetStationType 一致
        if (!isRouteMatch(cargoData)) {
            LOGGER.debug("[CargoDispatch] 货物目标类型 {} 与检测器类型 {} 不匹配，跳过",
                    cargoData.getTargetStationType(), stationType.getId());
            return;
        }

        // 2.5 绑定订单和玩家（手动提交时未绑定，必须在此设置，否则 giveReward 找不到玩家）
        String orderId = cargoData.getOrderId();
        if (orderId != null && !orderId.isEmpty()) {
            this.boundOrderId = orderId;
            UUID playerId = com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.getPlayer(orderId);
            if (playerId == null) {
                OrderData data = OrderManager.getOrder(orderId);
                if (data != null) playerId = data.getAcceptedPlayer();
            }
            this.boundPlayerId = playerId;
        }

        // 3. 从快照计算货物数量
        int cargoItemCount = 0;
        List<ItemStack> snapshot = CargoManager.getInventorySnapshot(subLevelUuid);
        if (snapshot != null) {
            for (ItemStack stack : snapshot) {
                if (!stack.isEmpty()) {
                    cargoItemCount += stack.getCount();
                }
            }
        }
        LOGGER.debug("[CargoDispatch] 物理化货物数量: {}, 订单ID: {}", cargoItemCount, cargoData.getOrderId());

        // 4. 删除前先清理货箱附近的绳子方块（按货箱真实世界 bbox，不误删无关绳子）
        // 原理：simulated mod 的绳子在 SubLevel 删除后仍保留指向它的约束，
        //       下次 physicsTick 重建约束时 Sable 验证失败崩溃（second body not a sub-level）
        //       删除绳子方块让 simulated 自然释放约束
        clearRopesForSubLevel(serverLevel, subLevel);

        // 5. 删除货箱：附着型只删载具 plot 内方块（载具保留）；独立型移除整个 SubLevel
        if (CargoManager.isAttachedVehicleCargo(subLevelUuid)) {
            CargoManager.removeAttachedCargoBlocks(serverLevel, subLevelUuid);
        } else {
            SubLevelScanner.removeSubLevel(serverLevel, subLevel);
            // 6. 注销 CargoManager 数据
            CargoManager.unregisterSubLevel(subLevelUuid);
        }

        // 7. 走通用的订单完成流程
        finalizeOrderCompletion(serverLevel, cargoData.getOrderId(), cargoItemCount);
    }

    /**
     * 处理检测到的静态货箱（未物理化）
     * 原理：通过 BFS 找到主方块，查询 CargoManager，然后连锁删除
     */
    private void handleStaticCargoDetected(Level level, BlockPos cargoPos) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos controllerPos = findControllerPos(level, cargoPos);
        if (controllerPos == null) {
            LOGGER.warn("[CargoDispatch] 未找到货箱主方块位置，cargoPos={}", cargoPos);
            return;
        }

        CargoData cargoData = CargoManager.query(controllerPos);
        if (cargoData == null) {
            LOGGER.warn("[CargoDispatch] 主方块 {} 无货运数据", controllerPos);
            return;
        }

        // 路线匹配：检测器的 stationType 必须与货物的 targetStationType 一致
        if (!isRouteMatch(cargoData)) {
            LOGGER.debug("[CargoDispatch] 静态货物目标类型 {} 与检测器类型 {} 不匹配，跳过",
                    cargoData.getTargetStationType(), stationType.getId());
            return;
        }

        // 绑定订单和玩家（手动提交时未绑定）
        String orderId = cargoData.getOrderId();
        if (orderId != null && !orderId.isEmpty()) {
            this.boundOrderId = orderId;
            UUID playerId = com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.getPlayer(orderId);
            if (playerId == null) {
                OrderData data = OrderManager.getOrder(orderId);
                if (data != null) playerId = data.getAcceptedPlayer();
            }
            this.boundPlayerId = playerId;
        }

        int cargoItemCount = 0;
        BlockEntity controllerBE = level.getBlockEntity(controllerPos);
        if (controllerBE instanceof CargoBlockEntity cargoBE) {
            SimpleContainer inv = cargoBE.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    cargoItemCount += stack.getCount();
                }
            }
        }
        LOGGER.debug("[CargoDispatch] 静态货物数量: {}, 订单ID: {}", cargoItemCount, cargoData.getOrderId());

        // BFS 一次得到货箱全部方块：按真实范围清绳，再统一删除（不再扫检测器全范围）
        Set<BlockPos> structure = bfsCargoBlocks(level, cargoPos);
        if (!structure.isEmpty()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : structure) {
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            }
            clearRopesInBox(serverLevel, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
            for (BlockPos p : structure) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        CargoManager.unregister(controllerPos);

        // 走通用的订单完成流程
        finalizeOrderCompletion(serverLevel, cargoData.getOrderId(), cargoItemCount);
    }

    /**
     * 通用的订单完成流程（物理化/静态货箱共用 / 手动提交共用）
     * 原理：原子认领（防并发重复发奖）→ 发放奖励 → 完成订单 → 通知删除路径点 → 播放音效 → 发送系统消息 → 清除绑定
     */
    private void finalizeOrderCompletion(ServerLevel level, String orderId, int cargoItemCount) {
        // 0. 记录本次完成的订单号（用于后续消息发送）
        String completedOrderId = boundOrderId != null ? boundOrderId : orderId;

        // 0.5 P2-1 幂等结算：原子认领订单。认领失败=已被并发的另一收货路径结算，
        //     直接返回，不重复发奖/通知/播音效（双检测器或检测器+手动提交同时命中时）
        if (completedOrderId == null) return;
        OrderData order = OrderManager.claimForCompletion(completedOrderId);
        if (order == null) {
            LOGGER.debug("[CargoDispatch] 订单 {} 已被其他收货路径结算，本次跳过", completedOrderId);
            return;
        }

        // 1. 货运币奖励结算到接单玩家所属公司账户（同时增加公司声望），不再发放实物
        int rewardMoney = order.getReward();
        if (rewardMoney > 0) {
            settleRewardIntoCompany(level, order, rewardMoney);
        }

        // 1.5 解析订单所属公司（与 settleRewardIntoCompany 同一套兜底逻辑），
        //     用于后续向「全体在线公司成员」广播完成通知（不再只通知接单玩家）。
        // 根因：奖励入公司账户，公司所有成员共享收益，完成提示理应全员可见；
        //       旧实现只给接单玩家发，导致「A 接单、B 送货」时 B 看不到任何完成反馈。
        UUID notifyCompanyId = order.getOwnerCompany();
        if (notifyCompanyId == null) {
            UUID pid = boundPlayerId;
            if (pid == null) pid = com.hzldm.createcargodispatch.cargo.OrderPlayerBinding
                    .getPlayer(completedOrderId);
            if (pid == null) pid = order.getAcceptedPlayer();
            if (pid != null) {
                notifyCompanyId = com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                        .getCompanyIdOfPlayer(pid);
            }
        }
        java.util.List<UUID> notifyMemberIds = (notifyCompanyId != null)
                ? com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                        .getCompany(notifyCompanyId).members()
                : java.util.List.of();

        // 2. 已认领订单标记完成并落盘（订单已从 ACCEPTED 摘除，无需也不能再调 completeOrder 重复摘除）
        OrderManager.finishClaimedOrder(order, level);

        // 2.5 货物已成功提交：发射 20 tick、强度 15 红石脉冲
        //     只检测不提交走 scanForCargoNoReceive，永远到不了这里，天然满足「不提交不发信号」
        emitRedstonePulse();

        // 3. 通知接单玩家删除 Xaero 路径点
        notifyRemoveWaypoint(level, completedOrderId);

        // 4. 播放完成音效：统一使用用户提供的「提示.ogg」（ORDER_NOTIFY）
        //    推送方式：给「全体在线公司成员」调 ServerPlayer.playNotifySound(PLAYERS 声道)
        //      - 忽略距离 / 位置直接推送，挂机在起点也能听到
        //      - PLAYERS 声道受 "玩家音量" 控制，一般都开着；BLOCKS 声道可能被关了
        //    同时保留给检测器附近所有人也放一次（氛围）
        if (completedOrderId != null && level.getServer() != null) {
            for (UUID mid : notifyMemberIds) {
                net.minecraft.server.level.ServerPlayer member = level.getServer().getPlayerList().getPlayer(mid);
                if (member == null) continue;
                try {
                    member.playNotifySound(
                            ModSounds.ORDER_NOTIFY.value(),
                            net.minecraft.sounds.SoundSource.PLAYERS,
                            0.85F, 1.0F);
                } catch (Throwable t) {
                    LOGGER.error("[CargoDispatch] completeOrder 播放 ORDER_NOTIFY 失败 玩家={},订单={}",
                            member.getName().getString(), completedOrderId, t);
                }
            }
            LOGGER.debug("[CargoDispatch] completeOrder→订单 {} 已向公司 {} 的 {} 名在线成员播放完成提示音",
                    completedOrderId, notifyCompanyId, notifyMemberIds.size());
        }
        // 附近玩家提示：近距离也能听到 ORDER_NOTIFY（保证不是接单玩家也能听到"这家伙完成订单了"）
        try {
            net.minecraft.sounds.SoundEvent notifySound = ModSounds.ORDER_NOTIFY.value();
            level.playSound(null, getBlockPos(), notifySound,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.4F, 1.0F);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] completeOrder 检测器附近播 ORDER_NOTIFY 失败 位置={},订单={}",
                    getBlockPos(), completedOrderId, t);
        }

        // 5. 给「全体在线公司成员」发送订单完成系统消息
        //    （合并了"奖励获得/提交成功/订单完成"，避免三层重复消息）
        if (completedOrderId != null && level.getServer() != null) {
            String shortOrderId = completedOrderId.length() > 8
                    ? completedOrderId.substring(0, 8)
                    : completedOrderId;
            Component completionMsg = Component.translatable(
                    "create_cargo_dispatch.order.completed",
                    shortOrderId, cargoItemCount, rewardMoney,
                    Component.translatable("create_cargo_dispatch.currency.coin"));
            for (UUID mid : notifyMemberIds) {
                net.minecraft.server.level.ServerPlayer member = level.getServer().getPlayerList().getPlayer(mid);
                if (member != null) {
                    member.sendSystemMessage(completionMsg);
                }
            }
            // 清理 OrderPlayerBinding 绑定
            com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.unbind(completedOrderId);
        }

        // 6. 清除绑定
        this.boundOrderId = null;
        this.boundPlayerId = null;
        this.lastDetectedCargoPos = null;
        setChanged();
    }

    /**
     * 删除货箱实际包围盒（外扩 2 格）内的绳子方块。
     * 原理：simulated 绳子在 SubLevel 删除后仍保留指向它的约束，
     *       下次 Sable physicsTick 重建约束时验证失败崩溃（second body not a sub-level），
     *       删除绳子方块让 simulated 自然释放约束。
     * 范围只覆盖货箱自身 bbox，不再扫检测器全范围，避免误删玩家无关绳子。
     */
    private void clearRopesInBox(Level level, BlockPos min, BlockPos max) {
        final int inflate = 2;
        int removed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX() - inflate; x <= max.getX() + inflate; x++) {
            for (int y = min.getY() - inflate; y <= max.getY() + inflate; y++) {
                for (int z = min.getZ() - inflate; z <= max.getZ() + inflate; z++) {
                    cursor.set(x, y, z);
                    BlockState st = level.getBlockState(cursor);
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    if (key != null && "simulated".equals(key.getNamespace())) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        removed++;
                    }
                }
            }
        }
        if (removed > 0) {
            LOGGER.info("[CargoDispatch] 收货前清理 {} 个绳子方块（货箱bbox外扩2，防 simulated 约束崩溃）", removed);
        }
    }

    /** 物理化货箱：按其世界 bbox 清绳；bbox 读取失败时仅清检测器相邻 3 格（小范围兜底） */
    private void clearRopesForSubLevel(ServerLevel sl, Object subLevel) {
        double[] gbb = SubLevelScanner.globalBBoxComponents(subLevel);
        if (gbb != null) {
            clearRopesInBox(sl,
                    BlockPos.containing(gbb[0], gbb[1], gbb[2]),
                    BlockPos.containing(gbb[3], gbb[4], gbb[5]));
        } else {
            BlockPos c = getBlockPos();
            clearRopesInBox(sl, c.offset(-3, -3, -3), c.offset(3, 3, 3));
        }
    }

    /**
     * 路线匹配检查（类型 + 编号双重校验）
     * 原理：
     *  - 编号层（同类型多站防错收的关键）：货物与检测器双方编号都非空时必须严格相等，
     *    任一为空（旧数据）则跳过编号校验
     *  - 类型层：货物 targetStationType 为 generic/空时放行（兼容旧数据）；
     *    detector stationType = GENERIC 时兜底放行；其余要求严格匹配
     */
    private boolean isRouteMatch(CargoData cargoData) {
        // 1. 目标站编号严格匹配：农场 A 的货箱不能提交到农场 B
        String cargoTargetId = cargoData.getTargetStationId();
        if (cargoTargetId != null && !cargoTargetId.isEmpty()
                && stationId != null && !stationId.isEmpty()
                && !cargoTargetId.equals(stationId)) {
            return false;
        }
        // 2. 类型匹配（旧逻辑保留）
        String cargoTargetType = cargoData.getTargetStationType();
        if (cargoTargetType == null || cargoTargetType.isEmpty() || "generic".equals(cargoTargetType)) {
            return true;
        }
        if (stationType == com.hzldm.createcargodispatch.cargo.StationType.GENERIC) {
            return true;
        }
        return cargoTargetType.equals(stationType.getId());
    }

    /**
     * 宽松版 hasTarget 检查（用于提交页面 / 检测器扫描）
     * 原 CargoData.hasTarget 要求 targetDimension != null && targetPos != ZERO，
     * 但实际订单在同维度时经常没有写入 targetDimension，或 targetDimension 为 null，
     * 这里只要 targetPos != ZERO 即视为有目标，避免漏检。
     */
    private boolean hasTargetLenient(CargoData cargoData) {
        if (cargoData == null) return false;
        net.minecraft.core.BlockPos tp = cargoData.getTargetPos();
        if (tp == null) return false;
        return !tp.equals(net.minecraft.core.BlockPos.ZERO);
    }

    /**
     * 静态货箱的统一 cargoData + 主方块位置查询（解决"识别不到货物"的根因）
     *
     * 原理（为什么需要统一 helper）：
     *  - 扫描到某 CargoBlock 时，该方块可能是 27 个方块中的非主方块，
     *    BlockEntity 里的 cargoData 字段可能因为 chunk 加载顺序 / 方块位置不同
     *    导致 setCargoData 没被调用到（即 targetStationType / sourceStationType / orderId 为空）
     *  - 即使 BlockEntity 里 cargoData 有值，也可能和 CargoManager 注册的"最新主数据"不同步
     *  - 所以统一查询顺序：
     *      1. 查 BLOCK_TO_MAIN_MAP（装配后 SubLevel 的反向映射，覆盖主/从方块）
     *      2. 再查 CargoManager.query(pos)（静态货箱的主方块注册数据）
     *      3. 最后用 cargoBE.getCargoData() 作为兜底（方块 NBT 里的 cargoData）
     *  - 3 种来源合并后，选"更完整"的一份（有 orderId / targetStationType 的优先）
     *
     * @return 不为 null = 有有效货物数据；主方块位置也一起返回
     */
    private StaticCargoResolve resolveStaticCargo(Level level, BlockPos pos) {
        BlockPos mainPos = null;
        // 1. BLOCK_TO_MAIN_MAP 反向映射（装配后 SubLevel 内部方块映射）
        BlockPos mappedMain = CargoManager.queryMainPos(pos);
        if (mappedMain != null) mainPos = mappedMain;
        // 2. findControllerPos：单方块直接返回 pos
        BlockPos controller = findControllerPos(level, pos);
        if (mainPos == null && controller != null) mainPos = controller;
        if (mainPos == null) mainPos = pos.immutable();

        CargoData fromManager = null;
        // 查主方块的注册数据
        fromManager = CargoManager.query(mainPos);
        if (fromManager == null) {
            // queryMainPos 的返回值对应的主方块
            if (mappedMain != null) fromManager = CargoManager.query(mappedMain);
        }
        // BLOCK_TO_MAIN_MAP 的 CargoData 也走 queryMainCargoData
        if (fromManager == null) {
            fromManager = CargoManager.queryMainCargoData(level, pos);
        }

        // 3. 兜底：本方块 BlockEntity 的 cargoData
        CargoData fromBE = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CargoBlockEntity cbe) fromBE = cbe.getCargoData();
        // 如果主方块 != pos，也查主方块的 BlockEntity
        CargoData fromMainBE = null;
        if (!mainPos.equals(pos)) {
            BlockEntity mainBE = level.getBlockEntity(mainPos);
            if (mainBE instanceof CargoBlockEntity cbe) fromMainBE = cbe.getCargoData();
        }

        CargoData best = pickBestCargoData(fromManager, fromBE, fromMainBE);
        boolean match = best != null && hasTargetLenient(best) && isRouteMatch(best);
        if (!match) return null;
        return new StaticCargoResolve(mainPos, best);
    }

    /** 从多个 CargoData 来源选"最完整"的一份（优先有 orderId 和 targetStationType 的） */
    @Nullable
    private CargoData pickBestCargoData(CargoData... list) {
        CargoData best = null;
        int bestScore = -1;
        for (CargoData d : list) {
            if (d == null) continue;
            int score = 0;
            if (!nullOrEmpty(d.getOrderId())) score += 8;
            if (!nullOrEmpty(d.getTargetStationType())
                    && !"generic".equals(d.getTargetStationType())) score += 4;
            if (!nullOrEmpty(d.getSourceStationType())) score += 2;
            if (hasTargetLenient(d)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static boolean nullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** 静态货箱解析结果：主方块位置 + 选中的 CargoData */
    private record StaticCargoResolve(BlockPos mainPos, CargoData cargoData) {}

    /**
     * 通过读取 CargoBlockEntity 的 controllerPos 查找货箱多方块结构的主方块位置
     * 原理：每个 CargoBlockEntity 的 controllerPos 都指向同一个 startPos，
     *      所以直接读取任一方块的 controllerPos 即可
     */
    private BlockPos findControllerPos(Level level, BlockPos startPos) {
        // 单方块设计：货箱自身即为主方块，直接返回 startPos
        BlockEntity be = level.getBlockEntity(startPos);
        if (be instanceof CargoBlockEntity) {
            return startPos;
        }
        return null;
    }

    /** BFS 收集与 startPos 连通的全部货箱方块（调用方负责后续删除） */
    private Set<BlockPos> bfsCargoBlocks(Level level, BlockPos startPos) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPos);
        visited.add(startPos);
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
        LOGGER.debug("[CargoDispatch] BFS 收集到 {} 个货箱方块", visited.size());
        return visited;
    }

    /**
     * 订单货运币奖励结算进公司账户（同时增加公司声望）。
     * 结算公司解析顺序：订单 ownerCompany → 接单玩家（检测器绑定/订单字段）当前所在公司。
     * 两者都没有（理论上接单强制公司身份，属异常兜底）：只记日志，不发放任何实物。
     *
     * @return true=成功入公司账户
     */
    private boolean settleRewardIntoCompany(ServerLevel level, OrderData order, int coin) {
        try {
            UUID companyId = order.getOwnerCompany();
            if (companyId == null) {
                UUID playerId = boundPlayerId;
                if (playerId == null) playerId = com.hzldm.createcargodispatch.cargo.OrderPlayerBinding
                        .getPlayer(order.getOrderId());
                if (playerId == null) playerId = order.getAcceptedPlayer();
                if (playerId != null) {
                    companyId = com.hzldm.createcargodispatch.company.CompanyStore.get(level)
                            .getCompanyIdOfPlayer(playerId);
                }
            }
            if (companyId == null) {
                LOGGER.warn("[CargoDispatch] 订单 {} 无结算公司（接单玩家未加入公司），货运币 {} 未入账",
                        order.getOrderId(), coin);
                return false;
            }
            return com.hzldm.createcargodispatch.company.CompanyService
                    .settleCompletedOrder(level, companyId, coin) != null;
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] 订单 {} 货运币结算异常", order.getOrderId(), t);
            return false;
        }
    }

    /**
     * 绑定订单信息
     * 原理：由 CargoGeneratorBlockEntity 在生成接收器时调用
     */
    public void bindOrder(String orderId, UUID playerId) {
        this.boundOrderId = orderId;
        this.boundPlayerId = playerId;
        setChanged();
        LOGGER.debug("[CargoDispatch] 接收器绑定订单: {} 玩家: {}", orderId, playerId);
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

    /** 获取最后一次检测到的货箱坐标（用于 Jade HUD 显示检测状态） */
    public BlockPos getLastDetectedCargoPos() {
        return lastDetectedCargoPos;
    }

    /** 获取当前绑定的订单ID（处理中的订单） */
    public String getBoundOrderId() {
        return boundOrderId;
    }

    /**
     * 玩家右键检测器：主动扫描并显示附近的第一个货箱（宽松匹配）
     *
     * 修复说明（为什么之前总是显示"未检测到货物"）：
     *  旧逻辑仅复用 lastDetectedCargoPos 缓存，而该字段只在 serverTick 的
     *  scanForCargoNoReceive / scanForCargo 中更新，且过滤非常严格：
     *  必须同时满足 hasTargetLenient + isRouteMatch（有目标坐标 + 类型匹配）。
     *  这导致：
     *    - 未绑定订单的空货箱被过滤掉 → 显示"未检测到"
     *    - targetStationType 与 detector 类型不匹配的货箱被过滤掉
     *    - world 刚加载或 chunk 刚加载时 serverTick 还没跑过 → lastDetectedCargoPos 恒为 null
     *  新逻辑：右键立即主动扫描，不过滤订单/类型，只要是 CargoBlock 或物理化 SubLevel 货箱
     *  就显示给玩家；同时更新 lastDetectedCargoPos 缓存。
     */
    public void onPlayerUse(Player player) {
        if (level == null) return;
        BlockPos selfPos = getBlockPos();

        // 1. 先尝试缓存：lastDetectedCargoPos 不为空 且 CargoData/inventory 仍然有效 → 直接显示
        if (lastDetectedCargoPos != null) {
            CargoData cachedData = CargoManager.query(lastDetectedCargoPos);
            SimpleContainer cachedInv = null;
            if (level.isLoaded(lastDetectedCargoPos)
                    && level.getBlockEntity(lastDetectedCargoPos) instanceof CargoBlockEntity cbe) {
                cachedInv = cbe.getInventory();
            }
            if (cachedData != null) {
                CargoInfoHelper.sendInfoToPlayer(player, cachedData, lastDetectedCargoPos, cachedInv);
                return;
            }
            // 缓存位置可能是 SubLevel 的 startPos 或属于某个 SubLevel 方块 → 用 START_TO_SUBLEVEL_MAP 反查 UUID
            UUID subUuid = CargoManager.getSubLevelUuidByStartPos(lastDetectedCargoPos);
            if (subUuid == null) subUuid = CargoManager.findSubLevelUuidContainingBlock(lastDetectedCargoPos);
            if (subUuid != null) {
                CargoData subData = CargoManager.getCargoDataBySubLevel(subUuid);
                SimpleContainer subInv = snapshotToContainer(CargoManager.getInventorySnapshot(subUuid));
                if (subData != null) {
                    CargoInfoHelper.sendInfoToPlayer(player, subData, lastDetectedCargoPos, subInv);
                    return;
                }
                // 即使 subData 为 null（极少见），也显示空 CargoData + 快照 inventory（不直接丢弃缓存）
                if (subInv != null) {
                    CargoInfoHelper.sendInfoToPlayer(player, new CargoData(), lastDetectedCargoPos, subInv);
                    return;
                }
            }
            // 缓存失效 → 清空并继续主动扫描
            lastDetectedCargoPos = null;
        }

        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        BlockPos scanCenter = ScanVolumes.centerAbove(selfPos);

        // 2. 优先扫描物理化 SubLevel 货箱（宽松：不过滤 hasTarget / isRouteMatch）
        if (SubLevelScanner.isAvailable() && level instanceof ServerLevel serverLevel) {
            List<Object> subLevels = SubLevelScanner.getNearbySubLevels(serverLevel, scanCenter, rangeXZ, rangeY);
            for (Object subLevel : subLevels) {
                UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
                if (uuid == null) continue;
                List<BlockPos> blocks = CargoManager.getBlocksBySubLevel(uuid);
                if (blocks == null || blocks.isEmpty()) continue;
                BlockPos startPos = CargoManager.getStartPosBySubLevel(uuid);
                if (startPos == null) startPos = blocks.get(0);
                // CargoData 兜底：SubLevel 无注册数据时用空对象，inventory 用快照
                CargoData cargoData = CargoManager.getCargoDataBySubLevel(uuid);
                if (cargoData == null) cargoData = new CargoData();
                SimpleContainer inv = snapshotToContainer(CargoManager.getInventorySnapshot(uuid));
                lastDetectedCargoPos = startPos;
                setChanged();
                CargoInfoHelper.sendInfoToPlayer(player, cargoData, startPos, inv);
                return;
            }
        }

        // 3. 再扫描静态 CargoBlock（宽松：不过滤 hasTarget / isRouteMatch，CargoData 可 null）
        BlockPos.MutableBlockPos cursor = scanCenter.mutable();
        Set<BlockPos> visitedMains = new HashSet<>();
        for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
            for (int dy = -rangeY; dy <= rangeY; dy++) {
                for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                    cursor.setWithOffset(scanCenter, dx, dy, dz);
                    if (!(level.getBlockState(cursor).getBlock() instanceof CargoBlock)) continue;
                    BlockPos curPos = cursor.immutable();
                    // 通过 findControllerPos 定位主方块（避免 27 个方块重复触发）
                    BlockPos mainPos = findControllerPos(level, curPos);
                    if (mainPos == null) mainPos = curPos;
                    if (!visitedMains.add(mainPos)) continue;

                    // 宽松合并 CargoData（多来源取最好的，允许全部为 null）
                    CargoData fromManager = CargoManager.query(mainPos);
                    if (fromManager == null) fromManager = CargoManager.queryMainCargoData(level, curPos);
                    CargoData fromCurBE = null;
                    BlockEntity be = level.getBlockEntity(curPos);
                    if (be instanceof CargoBlockEntity cbe) fromCurBE = cbe.getCargoData();
                    CargoData fromMainBE = null;
                    if (!mainPos.equals(curPos)) {
                        BlockEntity mbe = level.getBlockEntity(mainPos);
                        if (mbe instanceof CargoBlockEntity cbe) fromMainBE = cbe.getCargoData();
                    }
                    CargoData finalData = pickBestCargoData(fromManager, fromCurBE, fromMainBE);
                    if (finalData == null) finalData = new CargoData();

                    // inventory：主方块 BlockEntity 的 inventory 最可靠
                    SimpleContainer inv = null;
                    BlockEntity mainBE = level.getBlockEntity(mainPos);
                    if (mainBE instanceof CargoBlockEntity cbe) inv = cbe.getInventory();

                    lastDetectedCargoPos = mainPos;
                    setChanged();
                    CargoInfoHelper.sendInfoToPlayer(player, finalData, mainPos, inv);
                    return;
                }
            }
        }

        // 4. 真正一个货箱都没找到
        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.detector.no_cargo"));
    }

    /**
     * 把 CargoManager 的 inventory 快照（List<ItemStack>）转换成 SimpleContainer
     * 原理：sendInfoToPlayer 需要 SimpleContainer 用于 summarizeContents 合并显示
     * snapshot 中的 ItemStack 可能是稀疏的也可能是连续的，统一按顺序塞到新容器即可
     */
    @Nullable
    private SimpleContainer snapshotToContainer(@Nullable List<ItemStack> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        int size = Math.max(snapshot.size(), 1);
        SimpleContainer cont = new SimpleContainer(size);
        for (int i = 0; i < snapshot.size(); i++) {
            ItemStack s = snapshot.get(i);
            if (s != null && !s.isEmpty()) cont.setItem(i, s.copy());
        }
        return cont;
    }

    /**
     * 通知接单玩家删除 Xaero 路径点
     * 原理：
     *  - 优先从 OrderPlayerBinding 查询接单玩家（新版逻辑，目标检测器不在源站生成）
     *  - 回退到 boundPlayerId（旧版兼容，检测器预先绑定的情况）
     *  - 调用公共的 ModPayloads.notifyRemoveWaypoint 统一发送 RemoveWaypointPayload
     */
    private void notifyRemoveWaypoint(ServerLevel serverLevel, String orderId) {
        if (orderId == null) {
            return;
        }
        // 优先从全局绑定表查询（新版）
        UUID playerId = com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.getPlayer(orderId);
        // 回退到 BlockEntity 绑定（旧版兼容）
        if (playerId == null) {
            playerId = boundPlayerId;
        }
        if (playerId == null) {
            LOGGER.warn("[CargoDispatch] 订单 {} 无绑定玩家，跳过路径点删除通知", orderId);
            return;
        }

        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            LOGGER.debug("[CargoDispatch] 接单玩家不在线，跳过路径点删除通知");
            return;
        }
        com.hzldm.createcargodispatch.network.ModPayloads.notifyRemoveWaypoint(orderId, player);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (lastDetectedCargoPos != null) {
            tag.putInt("CargoX", lastDetectedCargoPos.getX());
            tag.putInt("CargoY", lastDetectedCargoPos.getY());
            tag.putInt("CargoZ", lastDetectedCargoPos.getZ());
        }
        // 注意：lastScanTick 不持久化
        // 原理：lastScanTick 是运行时状态，持久化后世界重新加载时 gameTime 重置为 0，
        //       而 lastScanTick 恢复为之前的大值，导致 gameTime - lastScanTick 为负数，
        //       永远小于 SCAN_INTERVAL，scanForCargo 永远不被调用
        if (boundOrderId != null) {
            tag.putString("BoundOrderId", boundOrderId);
        }
        tag.putString("StationType", stationType.getId());
        if (!stationId.isEmpty()) {
            tag.putString("StationId", stationId);
        }
        if (boundPlayerId != null) {
            tag.putUUID("BoundPlayerId", boundPlayerId);
        }
        tag.putBoolean("ShowRange", showRange);
    }

    /** 切换检测范围显示（服务端调用），返回切换后的状态 */
    public boolean toggleRangeDisplay() {
        this.showRange = !this.showRange;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return this.showRange;
    }

    public boolean isShowRange() {
        return showRange;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("CargoX")) {
            lastDetectedCargoPos = new BlockPos(
                    tag.getInt("CargoX"), tag.getInt("CargoY"), tag.getInt("CargoZ"));
        }
        // 注意：lastScanTick 不从 NBT 加载，保持初始值 0
        // 原理：避免世界重载后 gameTime 重置导致 scanForCargo 永远不执行
        if (tag.contains("BoundOrderId")) {
            boundOrderId = tag.getString("BoundOrderId");
        }
        // 仅在 nbt 显式包含 StationType 时才覆盖
        // 原理：子类（FarmDetectorBlockEntity 等）构造时已通过 setStationType 设置正确类型
        //       如果 nbt 没有此字段（旧数据或未保存），tag.getString 返回空串，
        //       StationType.byId("") 返回 GENERIC，会错误覆盖子类设置的 FARM/PASTURE 等类型
        if (tag.contains("StationType")) {
            stationType = StationType.byId(tag.getString("StationType"));
        }
        stationId = tag.getString("StationId");
        if (tag.hasUUID("BoundPlayerId")) {
            boundPlayerId = tag.getUUID("BoundPlayerId");
        }
        showRange = tag.getBoolean("ShowRange");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    // =========================================================================
    // 提交页面 UI 支持：收集货箱列表 + 手动强制提交
    // =========================================================================

    /**
     * 收集此检测器范围内可提交的货箱（station 提交页面调用）
     * 原理：与 scanForCargo 同样的扫描逻辑，但不触发收货，而是把所有匹配的条目追加到 out 列表
     */
    public void collectMatchableCargos(ServerLevel serverLevel, BlockPos selfPos,
                                       java.util.List<CargoStationBlockEntity.SubmittableCargo> out) {
        int rangeXZ = ModConfig.getDetectorRangeXZ();
        int rangeY = ModConfig.getDetectorRangeY();
        BlockPos scanCenter = ScanVolumes.centerAbove(selfPos);

        // 防止同一 SubLevel 货箱被多个 detector 重复扫描加入（多 detector 范围重叠或 GENERIC+同类型 都匹配）
        java.util.Set<UUID> addedSubLevelUuids = new java.util.HashSet<>();

        // 1. 扫描 SubLevel 货箱
        if (SubLevelScanner.isAvailable()) {
            java.util.List<Object> subLevels = SubLevelScanner.getNearbySubLevels(serverLevel, scanCenter, rangeXZ, rangeY);
            for (Object subLevel : subLevels) {
                UUID uuid = SubLevelScanner.getSubLevelUuid(subLevel);
                if (uuid == null) continue;
                if (!addedSubLevelUuids.add(uuid)) continue;  // 同一 SubLevel 只加入一次
                CargoData cargoData = CargoManager.getCargoDataBySubLevel(uuid);
                java.util.List<BlockPos> blocks = CargoManager.getBlocksBySubLevel(uuid);
                // SubLevel 也要求 lenient hasTarget（否则来源不明 / 没有 targetPos 的 SubLevel 会出现在提交页面）
                if (cargoData == null || !hasTargetLenient(cargoData)
                        || blocks == null || blocks.isEmpty() || !isRouteMatch(cargoData)) continue;
                BlockPos startPos = CargoManager.getStartPosBySubLevel(uuid);
                if (startPos == null) continue;
                java.util.List<ItemStack> snapshot = CargoManager.getInventorySnapshot(uuid);
                var pair = mergeDisplayItems(snapshot);
                String orderId = cargoData.getOrderId() != null ? cargoData.getOrderId() : "";
                String srcType = cargoData.getSourceStationType() != null ? cargoData.getSourceStationType() : "generic";
                out.add(new CargoStationBlockEntity.SubmittableCargo(
                        pair.display(), uuid, null, selfPos, pair.total(), orderId, srcType));
            }
        }

        // 2. 扫描静态 CargoBlock（使用 resolveStaticCargo 合并多来源数据，避免漏检）
        BlockPos.MutableBlockPos cursor = scanCenter.mutable();
        // 防止同一主方块被多次加入（多个 CargoBlock 扫描都会 resolve 到同一个 mainPos）
        java.util.Set<BlockPos> addedMains = new java.util.HashSet<>();
        for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
            for (int dy = -rangeY; dy <= rangeY; dy++) {
                for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                    cursor.setWithOffset(scanCenter, dx, dy, dz);
                    if (!(serverLevel.getBlockState(cursor).getBlock() instanceof CargoBlock)) continue;
                    StaticCargoResolve res = resolveStaticCargo(serverLevel, cursor.immutable());
                    // 与 SubLevel 路径严格对称：必须同时满足 hasTargetLenient + isRouteMatch
                    // 否则会把"无订单 / 空货箱 / 类型不匹配"的静态货箱混入提交页面，
                    // 造成"点了手动提交但 reward/complete 流程找不到 order"的 NPE
                    if (res == null || res.cargoData() == null
                            || !hasTargetLenient(res.cargoData())
                            || !isRouteMatch(res.cargoData())) continue;
                    BlockPos mainPos = res.mainPos();
                    if (!addedMains.add(mainPos)) continue;  // 同一主方块只加入一次
                    CargoData cargoData = res.cargoData();
                    // 从主方块读取 inventory（主方块 BlockEntity 的 inventory 最可靠）
                    java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
                    BlockEntity mainBE = serverLevel.getBlockEntity(mainPos);
                    SimpleContainer inv = (mainBE instanceof CargoBlockEntity cbe) ? cbe.getInventory() : null;
                    if (inv != null) {
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            ItemStack s = inv.getItem(i);
                            if (!s.isEmpty()) stacks.add(s.copy());
                        }
                    }
                    var pair = mergeDisplayItems(stacks);
                    String orderId = cargoData.getOrderId() != null ? cargoData.getOrderId() : "";
                    String srcType = cargoData.getSourceStationType() != null ? cargoData.getSourceStationType() : "generic";
                    out.add(new CargoStationBlockEntity.SubmittableCargo(
                            pair.display(), null, mainPos, selfPos, pair.total(), orderId, srcType));
                }
            }
        }
    }

    /**
     * 手动提交指定货箱（由提交页面按钮触发）
     *
     * @param subLevelUuid   SubLevel 货箱的 UUID（静态货箱为 null）
     * @param controllerPos  静态货箱主方块位置（SubLevel 货箱为 null）
     * @param serverLevel    服务端世界
     */
    public void forceSubmitCargo(@Nullable UUID subLevelUuid,
                                 @Nullable BlockPos controllerPos,
                                 ServerLevel serverLevel) {
        if (subLevelUuid != null) {
            // 1. SubLevel 货箱：读取缓存 → 直接调 handleSubLevelCargoDetected
            java.util.List<BlockPos> blocks = CargoManager.getBlocksBySubLevel(subLevelUuid);
            BlockPos start = CargoManager.getStartPosBySubLevel(subLevelUuid);
            Object subLevel = SubLevelScanner.findSubLevelByUuid(serverLevel, subLevelUuid);
            if (subLevel != null && start != null && blocks != null && !blocks.isEmpty()) {
                handleSubLevelCargoDetected(serverLevel, subLevel, subLevelUuid, start, blocks);
            }
        } else if (controllerPos != null) {
            // 2. 静态货箱：直接用 controllerPos 触发 handleStaticCargoDetected
            if (serverLevel.isLoaded(controllerPos)
                    && serverLevel.getBlockState(controllerPos).getBlock() instanceof CargoBlock) {
                handleStaticCargoDetected(serverLevel, controllerPos);
            }
        }
    }

    /** 合并多个 ItemStack 为显示列表（合并相同物品的 count）并返回总数 */
    private MergeResult mergeDisplayItems(@Nullable java.util.List<ItemStack> snapshot) {
        java.util.Map<ResourceLocation, ItemStack> merged = new java.util.LinkedHashMap<>();
        int total = 0;
        if (snapshot != null) {
            for (ItemStack s : snapshot) {
                if (s == null || s.isEmpty()) continue;
                total += s.getCount();
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
                ItemStack existing = merged.get(id);
                if (existing == null) {
                    merged.put(id, s.copy());
                } else {
                    existing.grow(s.getCount());
                }
            }
        }
        return new MergeResult(new java.util.ArrayList<>(merged.values()), total);
    }

    private record MergeResult(java.util.List<ItemStack> display, int total) {}

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
