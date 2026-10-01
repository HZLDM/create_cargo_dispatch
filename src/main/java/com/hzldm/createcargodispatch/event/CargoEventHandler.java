package com.hzldm.createcargodispatch.event;

import com.hzldm.createcargodispatch.block.CargoBlock;
import com.hzldm.createcargodispatch.cargo.CargoGeneratorRegistry;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.CargoPhysicsHelper;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.OrderPlayerBinding;
import com.hzldm.createcargodispatch.cargo.StationType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 货物事件处理器
 *
 * 原理：
 *  - 监听 LevelEvent.Load：服务器主世界首次加载时初始化订单池
 *  - 监听 LevelEvent.Unload：服务器停止时清理所有缓存，避免内存泄漏
 *  - 监听 BlockEvent.BreakEvent：阻止非创造模式破坏货箱方块（创造模式允许破坏）
 *  - 货箱的右键查看逻辑在 CargoBlock.useWithoutItem 中处理
 */
@EventBusSubscriber(modid = com.hzldm.createcargodispatch.CreateCargoDispatch.MODID)
public class CargoEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreateCargoDispatch");

    /**
     * 玩家登录：立即推送一次按该玩家连接站点过滤后的 PENDING 订单
     * 修复 Bug：不点开过货运站方块就不会同步到背包
     * 原理：
     *   - 兜底广播最多 5 秒才推一次，新玩家刚进服的前几秒背包订单视图仍是空的
     *   - 登录时单独 push 一次，做到 0 RTT 同步，配合 5 秒兜底广播保证数据完整
     *   - 仅对主世界生效（订单系统目前只在 OVERWORLD 运行）
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        try {
            // 1) 以公司存储为权威重建联络线绑定（内存索引重启后丢失），必须在订单过滤推送之前完成，
            //    否则公司成员的连接视图为空会导致订单被全部过滤掉
            com.hzldm.createcargodispatch.company.CompanyStore companyStore =
                    com.hzldm.createcargodispatch.company.CompanyStore.get(level);
            java.util.UUID companyId = companyStore.getCompanyIdOfPlayer(player.getUUID());
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level)
                    .rebuildBinding(player.getUUID(), companyId);
            // 2) 推送其公司连接列表（连接页 0 RTT 可见）
            com.hzldm.createcargodispatch.cargo.LinkageManager.get(level)
                    .pushLinkagesToPlayer(level, player.getUUID());
            // 3) 推送按公司连接过滤后的 PENDING 订单
            OrderManager.pushFilteredPendingOrdersToPlayer(level, player.getUUID());
            LOGGER.info("[CargoDispatch] PlayerLoggedIn：玩家 {} 登录（公司={}），已重建联络线绑定并推送初始同步",
                    player.getName().getString(), companyId);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] PlayerLoggedIn：初始同步给玩家 {} 失败",
                    player.getName().getString(), t);
        }
        // 联合运输公司状态 0 RTT 初始推送（在司→成员视图；不在司→可加入列表），不影响订单主流程
        try {
            com.hzldm.createcargodispatch.company.CompanyService.sendSync(player);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] PlayerLoggedIn：推送公司状态给玩家 {} 失败",
                    player.getName().getString(), t);
        }
        // 修复历史调试货箱物品：BLOCK_ENTITY_DATA 缺 "id" 会导致玩家存档保存崩溃
        try {
            repairCargoBlockEntityData(player);
        } catch (Throwable t) {
            LOGGER.error("[CargoDispatch] PlayerLoggedIn：修复货箱物品组件失败", t);
        }
    }

    /** 给背包内带 BLOCK_ENTITY_DATA 但缺 "id" 的货箱物品补上方块实体类型 id */
    private static void repairCargoBlockEntityData(ServerPlayer player) {
        String beId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO.get()).toString();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)
                    || !(blockItem.getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock)) {
                continue;
            }
            net.minecraft.world.item.component.CustomData cd =
                    stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            if (cd == null) continue;
            net.minecraft.nbt.CompoundTag tag = cd.copyTag();
            if (tag.contains("id")) continue;
            tag.putString("id", beId);
            stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
        }
    }

    /**
     * 世界加载时初始化订单池
     * 原理：仅在服务端 ServerLevel 触发，避免客户端误触发；
     *  OrderManager.seed 内部有 isEmpty 判断，重复触发也安全
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 仅在主世界触发一次种子初始化
        if (serverLevel.dimension() == Level.OVERWORLD) {
            OrderManager.seed(serverLevel);
            // 加载 SubLevel 持久化数据（重建内存映射）
            com.hzldm.createcargodispatch.cargo.CargoSubLevelStore.get(serverLevel);
        }
    }

    /**
     * 世界卸载时清理缓存
     * 原理：主世界卸载代表服务器停止，清理所有 static Map 避免内存泄漏
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.dimension() == Level.OVERWORLD) {
            OrderManager.clear();
            CargoManager.clear();
            CargoGeneratorRegistry.clear();
            OrderPlayerBinding.clear();
        }
    }

    /**
     * 主世界服务器 tick：驱动订单限时（PENDING 过期扫描）
     * 原理：
     *  - NeoForge 1.21.x 用 LevelTickEvent.Post（每个 ServerLevel 都会触发）
     *  - 仅过滤主世界 ServerLevel，交给 OrderManager.onServerLevelTick 执行：
     *      * 内部按配置的 expire_scan_interval_ticks 降频（默认每 100 tick 扫一次）
     *      * 不会每 tick O(n) 遍历订单池导致卡顿
     *  - 也顺便把 GeneratorBE 的 tryRefresh 依赖 tick 的问题解耦（即使没打开货物生成器 UI 的玩家，订单也会按时过期/刷新）
     */
    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        OrderManager.onServerLevelTick(serverLevel);
    }

    /**
     * 服务器启动后立刻建立 9合1 配方缓存（服务端主动构建一次，避免创建第一个货箱时才做 O(n) 扫描导致卡顿）
     * 原理：
     *  - ServerStartedEvent 触发时所有数据包已加载完毕，RecipeManager + RegistryAccess 完整可用
     *  - 之后若触发 /reload，由 AddReloadListener 只标记 dirty，**下次创建货箱或查质量时懒重建**
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        RecipeManager rm = server.getRecipeManager();
        // 必须用服务端 registryAccess() 传进 rebuild：Minecraft 1.21 getResultItem(RegistryAccess) 不能 null
        CargoPhysicsHelper.rebuildNineToOneBlockMap(rm, server.registryAccess());
        // 附着记录的对账由每个连接器 BE 在自身 serverTick 中按需完成，
        // 避免在启动时全局遍历区块（API 无稳定的已加载区块 Iterable）。
    }

    /**
     * 注册数据包重载监听器：/reload 或加入新数据包时
     * 懒加载策略：不立刻做 O(n) 全配方遍历，只把缓存标记为 dirty，下次需要时（创建货箱/查物品质量）再真正重建
     * 避免每次 /reload 都立刻占用主线程 CPU 扫描配方（服务器可能刚重载完卡）
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new net.minecraft.server.packs.resources.SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(net.minecraft.server.packs.resources.ResourceManager pResourceManager,
                                   net.minecraft.util.profiling.ProfilerFiller pProfiler) {
                return null;
            }

            @Override
            protected void apply(Void pObject,
                                 net.minecraft.server.packs.resources.ResourceManager pResourceManager,
                                 net.minecraft.util.profiling.ProfilerFiller pProfiler) {
                // ★ 懒加载：不立刻 rebuild，只标记 dirty，下次调用 getNineToOneBlock 或创建货箱时再重建
                CargoPhysicsHelper.markNineToOneDirty();
            }

            @Override
            public String getName() {
                return "CreateCargoDispatch_NineToOneCache";
            }
        });
    }

    /**
     * 货箱破坏事件处理
     *
     * 原理：
     *  - 非创造模式：阻止破坏，保护货物（防止生存玩家把货箱拆了走捷径）
     *  - 创造模式：允许直接破坏（连锁移除 SubLevel / 静态货箱方块 由 CargoBlock.playerWillDestroy 处理）
     *  - 货运站破坏：清理持久化站点位置 + 引用该站的订单
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        BlockState state = event.getState();
        if (state.getBlock() instanceof CargoBlock) {
            Player player = event.getPlayer();
            if (!player.isCreative()) {
                // 非创造模式禁止破坏货箱
                event.setCanceled(true);
            }
            // 创造模式放行（不做订单取消，连锁移除逻辑已在 playerWillDestroy 中）
            return;
        }
        // 货运站破坏处理（清理持久化连接数据）
        if (state.getBlock() instanceof com.hzldm.createcargodispatch.block.CargoStationBlock) {
            handleStationBlockBreak(event);
        }
    }

    /**
     * 处理货运站破坏
     * 原理：
     *  - 方块真正被破坏时清理持久化数据（StationLocationStore 和 LinkageManager）
     *  - 注意：setRemoved() 在 chunk 卸载时也会调用，所以不能在那里清理持久化数据
     *  - 只有 BlockEvent.BreakEvent 才表示玩家真正破坏了方块
     *  - 同时清除所有引用该站的 PENDING / ACCEPTED 订单（避免生成/接单无效路线）
     */
    private static void handleStationBlockBreak(BlockEvent.BreakEvent event) {
        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        BlockPos pos = event.getPos();
        // 从 BlockEntity 读取站点类型
        if (serverLevel.getBlockEntity(pos) instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE) {
            StationType stationType = stationBE.getStationType();
            // 清理站点位置存储
            com.hzldm.createcargodispatch.cargo.StationLocationStore store = com.hzldm.createcargodispatch.cargo.StationLocationStore.get(serverLevel);
            store.removeStation(stationType, pos);
            // 清理所有玩家的该站点连接记录
            com.hzldm.createcargodispatch.cargo.LinkageManager linkageMgr = com.hzldm.createcargodispatch.cargo.LinkageManager.get(serverLevel);
            linkageMgr.removeStationByPos(pos, serverLevel);
            // 清理所有引用该站点的订单（PENDING 移除，ACCEPTED 取消并通知玩家）
            com.hzldm.createcargodispatch.cargo.OrderManager.cancelOrdersReferencingStation(serverLevel, pos);
        }
    }
}
