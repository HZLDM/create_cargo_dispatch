package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.block.CargoDetectorBlock;
import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.blockentity.GeneratorSpawnMode;
import com.hzldm.createcargodispatch.cargo.CargoBalance;
import com.hzldm.createcargodispatch.cargo.CargoDimensions;
import com.hzldm.createcargodispatch.cargo.CargoManager;
import com.hzldm.createcargodispatch.cargo.LinkageManager;
import com.hzldm.createcargodispatch.cargo.OrderData;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.OrderPlayerBinding;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.item.SelectableStationItem;
import com.hzldm.createcargodispatch.item.StationItemConverter;
import com.hzldm.createcargodispatch.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 网络包注册与处理
 *
 * 原理：
 *  - 在 RegisterPayloadHandlersEvent 事件中注册所有 payload
 *  - 客户端→服务端：AcceptOrderPayload 在服务端处理
 *  - 服务端→客户端：SyncOrdersPayload、AddWaypointPayload 在客户端处理
 *
 * 配置阶段：
 *  - 使用 play 阶段（游戏内通信）
 *
 * 注册方式：
 *  - 由 CreateCargoDispatch 主类在构造函数中通过 modEventBus.addListener 注册
 *  - 不使用 @EventBusSubscriber，避免过时 API 警告
 */
public final class ModPayloads {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Network");

    private ModPayloads() {
    }

    /** 注册入口：由主类调用 */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(CreateCargoDispatch.MODID);
        registrar
                .playToServer(AcceptOrderPayload.TYPE, AcceptOrderPayload.STREAM_CODEC, ModPayloads::handleAcceptOrder)
                .playToServer(AbandonOrderPayload.TYPE, AbandonOrderPayload.STREAM_CODEC, ModPayloads::handleAbandonOrder)
                .playToServer(OpenOrdersMenuPayload.TYPE, OpenOrdersMenuPayload.STREAM_CODEC, ModPayloads::handleOpenOrdersMenu)
                .playToServer(OpenConnectedStationsPayload.TYPE, OpenConnectedStationsPayload.STREAM_CODEC, ModPayloads::handleOpenConnectedStations)
                .playToServer(ConnectStationPayload.TYPE, ConnectStationPayload.STREAM_CODEC, ModPayloads::handleConnectStation)
                .playToServer(DisconnectStationPayload.TYPE, DisconnectStationPayload.STREAM_CODEC, ModPayloads::handleDisconnectStation)
                .playToServer(ToggleStationNotifyPayload.TYPE, ToggleStationNotifyPayload.STREAM_CODEC, ModPayloads::handleToggleStationNotify)
                .playToServer(ToggleAllStationsNotifyPayload.TYPE, ToggleAllStationsNotifyPayload.STREAM_CODEC, ModPayloads::handleToggleAllStationsNotify)
                .playToServer(RequestSubmitListPayload.TYPE, RequestSubmitListPayload.STREAM_CODEC, ModPayloads::handleRequestSubmitList)
                .playToServer(SubmitCargoPayload.TYPE, SubmitCargoPayload.STREAM_CODEC, ModPayloads::handleSubmitCargo)
                .playToServer(ToggleAutoSubmitPayload.TYPE, ToggleAutoSubmitPayload.STREAM_CODEC, ModPayloads::handleToggleAutoSubmit)
                .playToServer(RequestStationOrdersPayload.TYPE, RequestStationOrdersPayload.STREAM_CODEC, ModPayloads::handleRequestStationOrders)
                .playToServer(ConvertStationItemPayload.TYPE, ConvertStationItemPayload.STREAM_CODEC, ModPayloads::handleConvertStationItem)
                .playToServer(SetGeneratorModePayload.TYPE, SetGeneratorModePayload.STREAM_CODEC, ModPayloads::handleSetGeneratorMode)
                .playToServer(SetCargoDimensionsPayload.TYPE, SetCargoDimensionsPayload.STREAM_CODEC, ModPayloads::handleSetCargoDimensions)
                .playToServer(UpdateDebugCargoTargetPayload.TYPE, UpdateDebugCargoTargetPayload.STREAM_CODEC, ModPayloads::handleUpdateDebugCargoTarget)
                .playToServer(UpdateDebugCargoFieldsPayload.TYPE, UpdateDebugCargoFieldsPayload.STREAM_CODEC, ModPayloads::handleUpdateDebugCargoFields)
                .playToServer(UpdateDebugCargoDimsPayload.TYPE, UpdateDebugCargoDimsPayload.STREAM_CODEC, ModPayloads::handleUpdateDebugCargoDims)
                .playToServer(ApplyDebugCargoPayload.TYPE, ApplyDebugCargoPayload.STREAM_CODEC, ModPayloads::handleApplyDebugCargo);
        // S2C 处理器引用客户端类，必须放在 @OnlyIn(Dist.CLIENT) 的 ClientPayloadHandlers 中，
        // 否则服务端加载 ModPayloads 时 RuntimeDistCleaner 会因常量池含 Screen/Minecraft 引用而崩溃。
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.hzldm.createcargodispatch.client.ClientPayloadHandlers.registerClient(registrar);
        }
    }

    // =========================================================================
    // 公共工具：订单路径点删除（所有删除路径点的地方都走这个入口）
    // =========================================================================

    /**
     * 通知指定玩家删除指定订单对应的 Xaero 路径点（**多命名体系兜底**）
     *
     * 为什么要多候选名？——项目里有两套完全独立的路径点命名体系：
     *   体系1：接单成功后服务端 AddWaypointPayload → 名字 "货运订单 #" + orderId → 指向目标货运站
     *   体系2：玩家点击新订单通知聊天栏坐标 → RUN_COMMAND 调 /createcargodispatch addwaypoint → 名字 "订单起点#" + orderId → 指向起点货箱
     *   此外还有用户短号显示可能误触（只显示前8位）、命令写错前缀等情况，全部一并删除。
     *
     * @param orderId 订单 ID（不要带前缀，方法内部会拼 6 种候选名依次删除）
     * @param player  目标玩家（不能为空）
     */
    public static void notifyRemoveWaypoint(String orderId, ServerPlayer player) {
        if (orderId == null || orderId.isEmpty() || player == null) return;

        // 短号（前8位），防止有些地方只显示了短号
        String shortId = orderId.length() > 8 ? orderId.substring(0, 8) : orderId;

        // 6 种候选名（找不到就静默跳过，多删不会报错）
        java.util.List<String> candidates = java.util.List.of(
                // 主命名体系（完整 orderId）
                "货运订单 #" + orderId,          // 体系1：目标货运站方向（AddWaypointPayload 创建）
                "订单起点#" + orderId,            // 体系2：点击聊天栏坐标创建的起点位置
                // 主命名体系（短号）
                "货运订单 #" + shortId,
                "订单起点#" + shortId,
                // 无前缀兜底（防止命令/代码写错前缀）
                orderId,
                shortId
        );
        for (String name : candidates) {
            RemoveWaypointPayload payload = new RemoveWaypointPayload(name);
            PacketDistributor.sendToPlayer(player, payload);
        }
        // 同步清理订单-玩家绑定（幂等：unbind 内部已做 null/空判断）
        com.hzldm.createcargodispatch.cargo.OrderPlayerBinding.unbind(orderId);
        LOGGER.debug("[CargoDispatch] 已通知玩家 {} 删除路径点候选 订单ID={} 候选数={}",
                player.getName().getString(), orderId, candidates.size());
    }

    /** 处理接单请求（服务端） */
    private static void handleAcceptOrder(AcceptOrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 公司门槛：无公司玩家不能接单
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) {
                return;
            }
            // 货运站位置
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            // 必须存在货运站 BE（打开 UI 的方块本体）
            BlockEntity stationEntity = player.level().getBlockEntity(stationPos);
            if (!(stationEntity instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBlock)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.station_not_found"));
                return;
            }
            // 玩家必须在 10 格内（与提交校验一致，防止远程发包）
            if (player.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5) > 100) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.too_far"));
                return;
            }
            // 只允许使用「本站同组绑定」的生成器（编号/类型匹配 + XZ20/Y8 内），
            // 不再全局找最近生成器——站旁没放生成器或生成器属于邻居站时直接拒单
            BlockPos genPos = com.hzldm.createcargodispatch.cargo.StationGroupHelper.findBoundGeneratorPos(
                    player.level(), stationPos, stationBlock.getStationType(), stationBlock.getStationId());
            if (genPos == null || !(player.level().getBlockEntity(genPos) instanceof CargoGeneratorBlockEntity generator)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.generator_not_linked"));
                return;
            }

            OrderData preCheckOrder = OrderManager.getOrder(payload.orderId());
            if (preCheckOrder == null) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.not_found"));
                return;
            }
            // —— 预校验：订单**必须是当前货运站作为起点发出的订单**（每个站点独立发单！）
            //    之前错误地允许「本站作为终点站」接其他站发的单，结果冶金厂站GUI里塞满了「矿山→冶金厂」，
            //    被玩家吐槽「为什么是别的地方的订单？」。这里严格修复：只允许在「订单起点站」接该站自己发的单。
            //    即便 CargoGeneratorMenu 已经按 isOrderStartBelongsToStation 过滤，也留一层服务器兜底：
            //    防止客户端过期缓存（其他站订单留在 GUI 里没刷新）或玩家手工发包乱接。
            // 以货运站本体类型/编号为准（绑定生成器类型必然与之兼容）
            StationType stationType = stationBlock.getStationType();
            if (!com.hzldm.createcargodispatch.cargo.OrderManager.isOrderStartBelongsToStation(
                    preCheckOrder, stationType, stationPos)) {
                String err;
                if (preCheckOrder.getStartPos() == null) err = "订单位置为空";
                else err = "订单是其他站（起点 " + preCheckOrder.getStartPos().toShortString()
                        + " 类型 " + (preCheckOrder.getStationType() != null ? preCheckOrder.getStationType().getId() : "?") + "）发出的，"
                        + "不属于当前站 " + stationPos.toShortString()
                        + "（类型 " + (stationType != null ? stationType.getId() : "?") + "）。每个站点独立发单，请前往订单起点站接取。";
                player.sendSystemMessage(Component.translatable(
                        "create_cargo_dispatch.order.station_broken_while_accepting", err));
                LOGGER.warn("[CargoDispatch] 拦截接单：订单 {} 的起点站 {}[{}] != 当前站 {}[{}]（每个站点独立发单），拒绝。",
                        preCheckOrder.getOrderId(),
                        preCheckOrder.getStartPos() != null ? preCheckOrder.getStartPos().toShortString() : "?",
                        preCheckOrder.getStationType() != null ? preCheckOrder.getStationType().getId() : "?",
                        stationPos, stationType != null ? stationType.getId() : "?");
                return;
            }
            // —— 联合运输属主兜底校验：个人订单仅属主可接、公司订单仅同公司成员可接 ——
            // 服务端权威校验，防止客户端过期缓存或手工构造数据包跨玩家/跨公司抢单
            if (!OrderManager.isOrderAccessibleAtStation(player.serverLevel(), player.getUUID(), preCheckOrder)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.order_forbidden"));
                LOGGER.warn("[CargoDispatch] 拦截接单：玩家 {} 无权访问订单 {}（属主隔离）",
                        player.getUUID(), preCheckOrder.getOrderId());
                return;
            }
            if (player.level() instanceof ServerLevel serverLevel) {
                // —— 接单校验规则（修复「指令生成订单无法接」Bug）：
                // 旧规则：玩家必须"同时连接了起站和终站"才让接单 → 过于严格。
                //   用 /createcargodispatch generateOrder 生成的订单根本不走"玩家连接站点"链路，所以被旧规则全部拦下，报：station_disconnected_while_accepting。
                // 新规则：只校验「起站方块 BE 仍存在 + 类型匹配」并且「终站方块 BE 仍存在 + 类型匹配」即可接单。
                //   - 站点还在，就能装货 / 交货；
                //   - 玩家与 LinkageManager 的"已连接站点集合"仅作为「是否显示该订单给该玩家（UI过滤/聊天过滤）」使用，不再作为接单门槛；
                //   - 「断开站点后取消公司已接订单」由 LinkageManager.disconnectStation / removeStationByPos → cancelAcceptedOrdersForCompanyAndStation 保证
                java.util.function.BiFunction<BlockPos, StationType, String> verifyStation = (pos, expectType) -> {
                    if (pos == null) return "位置为空";
                    // —— Step 1：订单位置精确匹配结构方块（普通情况）——
                    BlockEntity be = serverLevel.getBlockEntity(pos);
                    StationType realType = null;
                    if (be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE) {
                        realType = stationBE.getStationType();
                    } else if (be instanceof CargoDetectorBlockEntity detectorBE) {
                        realType = detectorBE.getStationType();
                    } else if (be instanceof CargoGeneratorBlockEntity generatorBE) {
                        realType = generatorBE.getStationType();
                    }
                    if (realType != null) {
                        // 类型一致性校验（GENERIC 视为兼容通配）
                        if (expectType != null && realType != expectType
                                && realType != StationType.GENERIC
                                && expectType != StationType.GENERIC) {
                            return "站 " + pos.toShortString() + " 类型不匹配（需要 " + expectType.getId() + " 实际 " + realType.getId() + "）";
                        }
                        return null; // 校验通过
                    }
                    // —— Step 2：订单位置没方块/不是结构方块（典型场景：取消订单重新放回PENDING后，原来的detector已被拆但站本体还在；
                    //             或订单生成时选了detector，但该detector之后被挖走）
                    //   做 XZ±20/Y±8 立方扫描找「离原pos最近的同类型 station/detector/generator」——只要还能找到，就视为站点结构仍然有效
                    //   （范围与 searchNearbyDetector / isSameStation 完全一致，保证单站内的位置漂移能找回，邻居站绝不会被错判为同站）。
                    final int rangeXZ = 20;
                    final int rangeY = 8;
                    BlockPos.MutableBlockPos cursor = pos.mutable();
                    long bestDist = Long.MAX_VALUE;
                    StationType bestType = null;
                    for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
                        for (int dy = -rangeY; dy <= rangeY; dy++) {
                            for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                                cursor.setWithOffset(pos, dx, dy, dz);
                                BlockEntity e2 = serverLevel.getBlockEntity(cursor);
                                StationType t2 = null;
                                if (e2 instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity s)      t2 = s.getStationType();
                                else if (e2 instanceof CargoDetectorBlockEntity d) t2 = d.getStationType();
                                else if (e2 instanceof CargoGeneratorBlockEntity g) t2 = g.getStationType();
                                else continue;
                                if (expectType != null && t2 != expectType
                                        && t2 != StationType.GENERIC
                                        && expectType != StationType.GENERIC) {
                                    continue; // 类型不匹配：不要把隔壁别的站当成目标
                                }
                                long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                                if (d < bestDist) {
                                    bestDist = d;
                                    bestType = t2;
                                }
                            }
                        }
                    }
                    if (bestType != null) {
                        // 找到了同站（附近）的结构方块，订单仍然有效（接单时 generateCargo 内部会用 generator 当前所在位置生成货箱，不用旧 startPos）
                        LOGGER.debug("[CargoDispatch] verifyStation：订单位置{}无结构方块，但在附近XZ20/Y8找到同类型站{}（distSqr={}），视为通过",
                                pos.toShortString(), bestType.getId(), bestDist);
                        return null;
                    }
                    return "位置 " + pos.toShortString() + " 没有货运站/检测器/生成器方块（已被破坏或不是结构方块）";
                };
                String startErr = verifyStation.apply(preCheckOrder.getStartPos(), preCheckOrder.getStationType());
                String targetErr = verifyStation.apply(preCheckOrder.getTargetPos(), preCheckOrder.getTargetStationType());
                if (startErr != null || targetErr != null) {
                    String reason = startErr != null ? "起始站:" + startErr : "目标站:" + targetErr;
                    player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.station_broken_while_accepting", reason));
                    LOGGER.info("[CargoDispatch] 拦截接单：订单 {} {} → 玩家{} 站点方块缺失或类型不匹配，拒绝",
                            preCheckOrder.getOrderId(), reason, player.getUUID());
                    return;
                }
            }

            OrderData order = generator.generateCargo(player, payload.orderId());
            if (order == null) {
                // 兜底：如果 generateCargo 内部某些极端失败分支（如嵌套异常）没调到 cancelOrder，这里强制调一次。
                // cancelOrder 内部先 removeAcceptedOrder，若已不在 ACCEPTED 池（内部已调过）则直接返回 null → 安全无副作用
                com.hzldm.createcargodispatch.cargo.OrderManager.cancelOrder(payload.orderId(), player.serverLevel());
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.accept_failed"));
                // 接单失败：单独给当前玩家「0RTT 推一次当前站订单」，不用等 2 秒广播合并窗口
                // 修复：取消后 GUI 「显示但怎么点都接不了 / 显示状态还是已被接走」的残留错觉
                StationType currentType = generator.getStationType();
                long nextRefresh = com.hzldm.createcargodispatch.cargo.OrderManager.getLastRefreshTime() + com.hzldm.createcargodispatch.cargo.OrderManager.getNextRefreshInterval();
                SyncOrdersPayload sync = new SyncOrdersPayload(
                        com.hzldm.createcargodispatch.cargo.OrderManager.getPendingOrdersForStation(
                                        player.serverLevel(), player.getUUID(), currentType, stationPos).stream()
                                .map(SyncOrdersPayload.OrderEntry::from)
                                .toList(),
                        nextRefresh);
                context.reply(sync);
                return;
            }

            ServerLevel serverLevel = player.serverLevel();
            // 触发订单数据持久化
            OrderManager.markChanged(serverLevel);

            // —— 接单成功后全局广播：PENDING 订单池少了一个订单，所有连接该起/终点站的玩家（包括"只在背包远程查看页、根本没开货运站方块GUI"的玩家）
            //    都需要立刻收到同步，防止"其他玩家继续看到已被接走的订单→再点接单→失败"的BUG
            // 为什么不能只 context.reply(sync) 给接单玩家？
            //    context.reply 只回复给「当前发 AcceptOrderPayload 的那个玩家」；其他在线玩家 ClientCargoCache.ORDERS 不会更新。
            //    通过 notifyPendingChanged 调度 broadcastPendingOrdersToAllPlayers（2秒合并窗口合并），所有玩家都能按自己连接过滤视图拿到最新。
            com.hzldm.createcargodispatch.cargo.OrderManager.notifyPendingChanged(serverLevel);

            // 接单成功：按「当前站 stationPos」精确过滤重新同步当前站的订单（给当前接单玩家单独再推一次，0RTT 刷新，不用等广播合并窗口）
            // 修复：之前 getPendingOrders(currentType) 按类型全球返回 → 把别的站订单又塞进当前站ClientCache里，导致接单后
            //       当前站订单Tab 「时而显示别的站订单」继续出现；改成 getPendingOrdersForStation(currentType, stationPos) 后
            //       与 CargoGeneratorMenu 构造口径、广播给其他玩家的过滤口径（isOrderVisibleToPlayer）完全一致。
            StationType currentType = generator.getStationType();
            long nextRefresh = com.hzldm.createcargodispatch.cargo.OrderManager.getLastRefreshTime() + com.hzldm.createcargodispatch.cargo.OrderManager.getNextRefreshInterval();
            SyncOrdersPayload sync = new SyncOrdersPayload(
                    com.hzldm.createcargodispatch.cargo.OrderManager.getPendingOrdersForStation(
                                    player.serverLevel(), player.getUUID(), currentType, stationPos).stream()
                            .map(SyncOrdersPayload.OrderEntry::from)
                            .toList(),
                    nextRefresh);
            context.reply(sync);

            // 通知客户端添加路径点
            String dimStr = order.getTargetDimension() != null ? order.getTargetDimension().toString() : "minecraft:overworld";
            String name = "货运订单 #" + order.getOrderId();
            AddWaypointPayload wp = new AddWaypointPayload(
                    order.getTargetPos().getX(),
                    order.getTargetPos().getY(),
                    order.getTargetPos().getZ(),
                    dimStr, name, "货"
            );
            context.reply(wp);

            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.accepted",
                    order.getOrderId(),
                    order.getTargetPos().getX(),
                    order.getTargetPos().getY(),
                    order.getTargetPos().getZ()));

            // 接单成功：播放用户提供的「提示.ogg」（ORDER_NOTIFY）
            // 原理：服务端用 playNotifySound 给指定玩家单独推送音效，其他玩家听不到；PLAYERS 声道不受"方块音量"影响
            try {
                player.playNotifySound(
                        ModSounds.ORDER_NOTIFY.value(),
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.85f, // 用户自定义音效，给 85% 音量保证听得清
                        1.0f  // 音调正常
                );
                LOGGER.debug("[CargoDispatch] handleAcceptOrder→玩家 {} 接单成功 已播放 ORDER_NOTIFY 订单={}",
                        player.getName().getString(), order.getOrderId());
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] handleAcceptOrder 接单音效播放失败 玩家={},订单={}",
                        player.getName().getString(), order.getOrderId(), t);
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理接单请求失败", ex);
            return null;
        });
    }

    /**
     * 处理放弃订单请求（服务端）
     *
     * 原理：
     *  - 1. 查找订单 → 2. 删除货箱方块 → 3. 放弃订单 → 4. 通知客户端删除路径点
     *  - 5. 重新同步活跃订单列表
     */
    private static void handleAbandonOrder(AbandonOrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) {
                return;
            }
            String orderId = payload.orderId();
            OrderData order = OrderManager.getOrder(orderId);
            if (order == null) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.not_found"));
                return;
            }
            // 验证放弃权限：个人单仅配送者本人；公司单同公司任意成员都可放弃（联合运输协作兜底）
            boolean canAbandon = player.getUUID().equals(order.getAcceptedPlayer());
            if (!canAbandon && order.getOwnerCompany() != null) {
                try {
                    canAbandon = com.hzldm.createcargodispatch.company.CompanyStore.get(player.serverLevel())
                            .isMember(order.getOwnerCompany(), player.getUUID());
                } catch (Throwable t) {
                    LOGGER.error("[CargoDispatch] 放弃订单查询公司成员失败，保守拒绝 order={}", orderId, t);
                }
            }
            if (!canAbandon) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.not_yours"));
                return;
            }

            // 删除货箱方块（通过订单的 SubLevel UUID 精确删除）
            removeCargoBlocks(player.level(), order);

            ServerLevel serverLevel = player.serverLevel();
            if (order.isManualDebug()) {
                // 调试货箱手工订单：删公司订单（不放回公共池）+ 货箱实体已由 removeCargoBlocks 移除
                OrderManager.deleteManualOrder(orderId, serverLevel);
                notifyRemoveWaypoint(orderId, player);
                // 向公司全体在线成员重推活跃订单
                resyncCompanyActiveOrders(serverLevel, order.getOwnerCompany(), player.getUUID());
                player.sendSystemMessage(Component.translatable(
                        "create_cargo_dispatch.order.manual_removed", orderId));
                return;
            }
            // 放弃订单（放回待接单池），并传入 ServerLevel 让 OrderManager 自动调度广播同步
            // 修复 Bug：玩家放弃后订单回PENDING池，但其他玩家（背包远程查看页/货运站现场UI）不刷新显示
            OrderManager.abandonOrder(orderId, serverLevel);
            OrderPlayerBinding.unbind(orderId);
            // 触发订单数据持久化
            OrderManager.markChanged(serverLevel);

            // 通知客户端删除路径点（notifyRemoveWaypoint 内部会发 6 种候选名的 RemoveWaypointPayload）
            notifyRemoveWaypoint(orderId, player);

            // 重新同步活跃订单列表（含同公司成员配送中的订单）
            SyncActiveOrdersPayload syncPayload = new SyncActiveOrdersPayload(
                    OrderManager.getAcceptedOrdersByPlayer(serverLevel, player.getUUID()).stream()
                            .map(SyncActiveOrdersPayload.ActiveOrderEntry::from)
                            .toList());
            context.reply(syncPayload);

            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.order.abandoned", orderId));
        }).exceptionally(ex -> {
            LOGGER.error("处理放弃订单失败", ex);
            return null;
        });
    }

    /** 向公司全体在线成员重推活跃订单（手工订单取消后同步，含操作者自身） */
    private static void resyncCompanyActiveOrders(ServerLevel level, java.util.UUID companyId, java.util.UUID actor) {
        if (companyId == null) return;
        com.hzldm.createcargodispatch.company.CompanyStore store =
                com.hzldm.createcargodispatch.company.CompanyStore.get(level);
        for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            if (!store.isMember(companyId, online.getUUID())) continue;
            List<SyncActiveOrdersPayload.ActiveOrderEntry> entries = OrderManager
                    .getAcceptedOrdersByPlayer(level, online.getUUID()).stream()
                    .map(SyncActiveOrdersPayload.ActiveOrderEntry::from)
                    .toList();
            PacketDistributor.sendToPlayer(online, new SyncActiveOrdersPayload(entries));
        }
    }

    /**
     * 删除订单对应的货箱
     * 原理：
     *  - 优先通过订单存储的 subLevelUuid 精确定位并删除物理化 SubLevel
     *  - 每个订单有独立的 UUID，避免多个订单共享同一 cargoPos 时误删
     *  - 如果 SubLevel 不可用（装配失败或已卸载），降级为方块删除
     *
     * @param level 服务端 Level
     * @param order 订单数据（含 startPos 和 subLevelUuid）
     */
    public static void removeCargoBlocks(Level level, OrderData order) {
        if (level.isClientSide || order == null) return;
        BlockPos startPos = order.getStartPos();
        java.util.UUID subLevelUuid = order.getSubLevelUuid();

        // 优先通过订单的 SubLevel UUID 精确删除
        if (subLevelUuid != null && level instanceof ServerLevel serverLevel) {
            // 附着型：货箱方块在载具 plot 内，只删货箱方块，载具本身必须保留
            if (com.hzldm.createcargodispatch.cargo.CargoManager.isAttachedVehicleCargo(subLevelUuid)) {
                com.hzldm.createcargodispatch.cargo.CargoManager.removeAttachedCargoBlocks(serverLevel, subLevelUuid);
                LOGGER.info("[CargoDispatch] 已删除载具附着货箱，载具保留 订单={} UUID={}",
                        order.getOrderId(), subLevelUuid);
                return;
            }
            Object subLevel = com.hzldm.createcargodispatch.cargo.SubLevelScanner.findSubLevelByUuid(serverLevel, subLevelUuid);
            if (subLevel != null) {
                boolean removed = com.hzldm.createcargodispatch.cargo.SubLevelScanner.removeSubLevel(serverLevel, subLevel);
                if (removed) {
                    CargoManager.unregisterSubLevel(subLevelUuid);
                    LOGGER.info("[CargoDispatch] 已通过 SubLevel 删除物理化货物 订单={} UUID={}",
                            order.getOrderId(), subLevelUuid);
                    return;
                }
                LOGGER.warn("[CargoDispatch] SubLevel 删除失败，降级为方块删除 订单={}", order.getOrderId());
            } else {
                LOGGER.warn("[CargoDispatch] 未找到 SubLevel {}，可能是已卸载，清理缓存后降级 订单={}",
                        subLevelUuid, order.getOrderId());
                CargoManager.unregisterSubLevel(subLevelUuid);
            }
        }

        // 降级：方块删除（BFS 搜索连通的 CargoBlock）
        // 起点定位：优先用「该订单在 CargoManager 注册的主方块位置」。
        // 原理：订单的 startPos 是站/检测器位置（已不再被 generateCargo 改写为货箱位置），
        //       而货箱主方块位置可以从 CARGO_MAP（静态货箱）反查；两者都找不到才报错。
        BlockPos cargoStart = startPos;
        if (cargoStart == null || !(level.getBlockState(cargoStart).getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock)) {
            BlockPos byOrder = CargoManager.findStartPosByOrderId(order.getOrderId());
            if (byOrder != null) cargoStart = byOrder;
        }
        if (cargoStart == null) {
            LOGGER.warn("[CargoDispatch] 订单 {} 无 startPos，无法降级方块删除", order.getOrderId());
            return;
        }
        // 防御性检查：起点必须是 CargoBlock 才进行删除
        if (!(level.getBlockState(cargoStart).getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock)) {
            LOGGER.warn("[CargoDispatch] 起点 {} 不是货箱方块，跳过方块删除 订单={}", cargoStart, order.getOrderId());
            CargoManager.unregister(cargoStart);
            return;
        }
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(cargoStart);
        visited.add(cargoStart);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.contains(next) && level.getBlockState(next).getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        // 注销 CargoManager 数据
        CargoManager.unregister(cargoStart);
        // 移除所有货箱方块
        for (BlockPos p : visited) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        LOGGER.info("[CargoDispatch] 已删除货箱方块 {} 个 @ {} 订单={}", visited.size(), cargoStart, order.getOrderId());
    }

    /**
     * 处理打开订单菜单请求（服务端）
     * 原理：
     *  - 客户端背包按钮发送 OpenOrdersMenuPayload
     *  - 服务端调用 player.openMenu() 打开 PlayerOrdersMenu
     *  - NeoForge 自动同步到客户端打开 PlayerOrdersScreen
     */
    private static void handleOpenOrdersMenu(OpenOrdersMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 同步活跃订单列表（联合运输成员可见同公司其他成员配送中的订单）
            List<SyncActiveOrdersPayload.ActiveOrderEntry> activeOrders =
                    OrderManager.getAcceptedOrdersByPlayer(player.serverLevel(), player.getUUID()).stream()
                            .map(SyncActiveOrdersPayload.ActiveOrderEntry::from)
                            .toList();
            PacketDistributor.sendToPlayer(player, new SyncActiveOrdersPayload(activeOrders));

            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.hzldm.createcargodispatch.menu.PlayerOrdersMenu(id, inv),
                    Component.translatable("create_cargo_dispatch.order.my_orders")
            ));
        }).exceptionally(ex -> {
            LOGGER.error("处理打开订单菜单失败", ex);
            return null;
        });
    }

    /**
     * 处理打开已连接站点页请求（服务端）
     * 原理：
     *  - 客户端标签点击发送 OpenConnectedStationsPayload
     *  - 服务端同步已连接站点列表并打开 ConnectedStationsMenu
     */
    private static void handleOpenConnectedStations(OpenConnectedStationsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 同步已连接站点列表给客户端
            ServerLevel serverLevel = player.serverLevel();
            LinkageManager linkageMgr = LinkageManager.get(serverLevel);
            List<SyncLinkagesPayload.LinkageEntry> linkages = linkageMgr.getConnectedStations(player.getUUID()).stream()
                    .map(s -> new SyncLinkagesPayload.LinkageEntry(
                            s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), s.notifyEnabled()))
                    .toList();
            PacketDistributor.sendToPlayer(player, new SyncLinkagesPayload(linkages));

            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.hzldm.createcargodispatch.menu.ConnectedStationsMenu(id, inv),
                    Component.translatable("create_cargo_dispatch.tab.connected")
            ));
        }).exceptionally(ex -> {
            LOGGER.error("处理打开已连接站点页失败", ex);
            return null;
        });
    }

    /**
     * 处理断开站点请求（服务端）
     * 原理：
     *  - 玩家在连接页点击「断开」按钮
     *  - 从 LinkageManager 移除指定站点
     *  - 重新同步已连接站点列表给客户端
     */
    private static void handleDisconnectStation(DisconnectStationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) return;
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            ServerLevel serverLevel = player.serverLevel();
            LinkageManager linkageMgr = LinkageManager.get(serverLevel);
            // 断开后开启 2 分钟重连冷却（disconnectStation(..., serverLevel)）
            boolean success = linkageMgr.disconnectStation(player.getUUID(), stationPos, serverLevel);
            if (success) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.disconnected_with_cooldown",
                        LinkageManager.DISCONNECT_RECONNECT_COOLDOWN_TICKS / 20));
                // 重新同步已连接站点列表
                List<SyncLinkagesPayload.LinkageEntry> linkages = linkageMgr.getConnectedStations(player.getUUID()).stream()
                        .map(s -> new SyncLinkagesPayload.LinkageEntry(
                                s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), s.notifyEnabled()))
                        .toList();
                PacketDistributor.sendToPlayer(player, new SyncLinkagesPayload(linkages));
            } else {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.not_connected"));
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理断开站点失败", ex);
            return null;
        });
    }

    /**
     * 处理「单站提示开关」请求（服务端）
     * 原理：
     *  - 客户端发送 ToggleStationNotifyPayload（pos + enabled）
     *  - 服务端调用 LinkageManager.setStationNotifyEnabled → 修改内存 + SavedData dirty + push 同步客户端
     *  - pushLinkagesToPlayer 内部会重新发 SyncLinkagesPayload，UI 渲染天然联动（单站按钮/总开关按钮都吃同一份数据）
     */
    private static void handleToggleStationNotify(ToggleStationNotifyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) return;
            ServerLevel serverLevel = player.serverLevel();
            LinkageManager linkageMgr = LinkageManager.get(serverLevel);
            boolean changed = linkageMgr.setStationNotifyEnabled(
                    serverLevel,
                    player.getUUID(),
                    payload.stationPos(),
                    payload.enabled());
            if (changed) {
                LOGGER.debug("[CargoDispatch] 玩家 {} 站点 {} notifyEnabled→{}",
                        player.getName().getString(), payload.stationPos(), payload.enabled());
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理切换单站提示开关失败", ex);
            return null;
        });
    }

    /**
     * 处理「一键全部站点提示开关」请求（服务端）
     * 原理：
     *  - 客户端点击底部"一键全部开/关"发送 ToggleAllStationsNotifyPayload(enabled)
     *  - 服务端一次性修改该玩家所有已连接 ConnectedStation.notifyEnabled
     *  - 修改完成后 pushLinkagesToPlayer → 客户端每个条目的按钮都会同步成新状态 → 联动
     */
    private static void handleToggleAllStationsNotify(ToggleAllStationsNotifyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) return;
            ServerLevel serverLevel = player.serverLevel();
            LinkageManager linkageMgr = LinkageManager.get(serverLevel);
            int changed = linkageMgr.setAllStationsNotifyEnabled(
                    serverLevel,
                    player.getUUID(),
                    payload.enabled());
            LOGGER.debug("[CargoDispatch] 玩家 {} 一键设置全部站 notifyEnabled={}，实际变更条数={}",
                    player.getName().getString(), payload.enabled(), changed);
        }).exceptionally(ex -> {
            LOGGER.error("处理一键切换所有站点提示开关失败", ex);
            return null;
        });
    }

    /**
     * 处理连接站点请求（服务端）
     * 原理：
     *  - 玩家在货运站UI点击"建立联络线"按钮
     *  - 直接从 BlockEntity 读取 stationType
     *  - 调用 LinkageManager.connectStation 记录到玩家的已连接列表
     *  - 连接成功后自动为所有已连接的不同类型站点之间生成订单
     *  - 同步联络线列表和当前站点类型的订单给客户端
     */
    private static void handleConnectStation(ConnectStationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 公司门槛：必须加入或创建联合运输公司才能进行货运操作
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) {
                return;
            }
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            ServerLevel serverLevel = player.serverLevel();

            // 直接从 BlockEntity 读取站点类型
            BlockEntity be = serverLevel.getBlockEntity(stationPos);
            if (!(be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.source_not_found"));
                return;
            }
            StationType stationType = stationBE.getStationType();
            if (stationType == null || stationType == StationType.GENERIC) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.source_not_found"));
                return;
            }

            // 搜索附近同类型的检测器方块，用检测器位置作为订单目标
            // 原理：订单的 targetPos 用于 Xaero 路径点，应指向检测器而非货运站
            //       货运站和检测器在结构中是不同方块，位置不同
            BlockPos detectorPos = searchNearbyDetector(serverLevel, stationPos, stationType);
            BlockPos connectPos = detectorPos != null ? detectorPos : stationPos;
            LOGGER.info("[CargoDispatch] 连接站点：货运站={}, 检测器={}, 使用位置={}",
                    stationPos, detectorPos, connectPos);

            LinkageManager linkageMgr = LinkageManager.get(serverLevel);
            // 新 connectStation 带 gameTickNow：检查"断开→重连"2 分钟冷却
            LinkageManager.ConnectResult connectResult = linkageMgr.connectStation(
                    serverLevel, player.getUUID(), connectPos, stationType, serverLevel.getGameTime());
            if (connectResult.noCompany()) {
                // LinkageManager 兜底（理论上前置门槛已拦）
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.company.required"));
                return;
            }
            if (connectResult.duplicated()) {
                // 已连接 → 提示一下即可，不改变玩家状态
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.already_connected"));
                return;
            }
            if (connectResult.limitReached()) {
                // 声望等级对应的可连接站点槽位已满
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.limit_reached",
                        connectResult.currentCount(), connectResult.limitSlots()));
                return;
            }
            if (connectResult.cooldownSecs() > 0) {
                // 冷却中 → 明确告诉剩余秒数
                player.sendSystemMessage(Component.translatable(
                        "create_cargo_dispatch.linkage.cooldown", connectResult.cooldownSecs()));
                return;
            }
            if (!connectResult.connected()) {
                // GENERIC 或其他未知失败
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.failed"));
                return;
            }
            // ✅ 真·连接成功
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.connected"));
            // 同步已连接站点列表
            List<SyncLinkagesPayload.LinkageEntry> linkages = linkageMgr.getConnectedStations(player.getUUID()).stream()
                    .map(s -> new SyncLinkagesPayload.LinkageEntry(
                            s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), s.notifyEnabled()))
                    .toList();
            LOGGER.info("[CargoDispatch] 连接成功：玩家={} 站点={}({}) 公司已连接总数={}",
                    player.getUUID(), stationPos, stationType.getId(), linkages.size());
            // 连接是公司资产：推给公司全体在线成员，其他成员的连接页/订单视图即时同步
            linkageMgr.pushCompanyLinkagesToMembers(serverLevel, player.getUUID());

            // 防刷单：仅当连接的是「新类型」站点时才生成订单
            boolean isNewType = !linkageMgr.hasGeneratedType(player.getUUID(), stationType);
            if (isNewType) {
                // 检查玩家是否已连接至少两种不同类型（订单需要源+目标两种类型）
                java.util.List<StationType> connectedTypes = linkageMgr.getConnectedTypes(player.getUUID());
                long distinctCount = connectedTypes.stream()
                        .filter(t -> t != StationType.GENERIC)
                        .count();
                if (distinctCount >= 2) {
                    // ✅ 需求2：连接成功后**只给"最后刚连接的这一个货运站"**生成订单
                    //         （不再遍历所有已连接类型批量 ensureOrders，避免同类型多站重复刷）
                    // 批量通知合并：即便只生成一个也用 begin/endBatch，保持提示音/汇总消息一致
                    int totalGenerated = 0;
                    com.hzldm.createcargodispatch.cargo.OrderManager.beginBatchNotification();
                    try {
                        // 直接以「刚连接的 stationPos（货运站本体方块位置）」作为源站点位置，
                        // generateOrderForStation 内部会自动把 startPos 统一转为 detectorPos 优先
                        com.hzldm.createcargodispatch.cargo.OrderData newOrder =
                                com.hzldm.createcargodispatch.cargo.OrderManager.generateOrderForStation(
                                        serverLevel, player.getUUID(), stationPos, stationType);
                        if (newOrder != null) totalGenerated = 1;
                    } finally {
                        com.hzldm.createcargodispatch.cargo.OrderManager.endBatchNotification(serverLevel);
                    }
                    // 标记所有已连接类型为已生成（保持原有防刷单调性行为一致：再连同类型不触发生成）
                    for (StationType type : connectedTypes) {
                        if (type != StationType.GENERIC) {
                            linkageMgr.markTypeGenerated(player.getUUID(), type);
                        }
                    }
                    if (totalGenerated > 0) {
                        OrderManager.markChanged(serverLevel);
                        player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.orders_refreshed", totalGenerated));
                        LOGGER.info("[CargoDispatch] 连接最后站点 {}（{}）→ 仅为该站生成 {} 个订单（玩家={}，distinctTypes={}）",
                                stationPos, stationType.getId(), totalGenerated, player.getUUID(), distinctCount);
                    }
                } else {
                    // 只连接一种类型，标记该类型为已生成，避免下次连接同类型再检查
                    linkageMgr.markTypeGenerated(player.getUUID(), stationType);
                    player.sendSystemMessage(Component.translatable("create_cargo_dispatch.linkage.need_other_type"));
                }
            } else {
                LOGGER.info("[CargoDispatch] 玩家 {} 连接的 {} 类型已生成过订单，不生成新订单（防刷单）",
                        player.getUUID(), stationType.getId());
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理连接站点失败", ex);
            return null;
        });
    }

    /**
     * 搜索附近的同类型检测器方块
     * 原理：
     *  - 在货运站附近搜索检测器方块（CargoDetectorBlock 子类）
     *  - 检测器 BlockEntity 的 stationType 必须与货运站一致
     *  - 找到则返回检测器位置，用于订单的 targetPos（Xaero 路径点）
     *  - 找不到则返回 null（调用方回退到货运站位置）
     *
     * @param level      服务端维度
     * @param center     货运站位置（搜索中心）
     * @param targetType 目标站点类型
     * @return 检测器位置，或 null
     */
    public static BlockPos searchNearbyDetectorForOrderPos(ServerLevel level, BlockPos center, StationType targetType) {
        return searchNearbyDetector(level, center, targetType);
    }

    private static BlockPos searchNearbyDetector(ServerLevel level, BlockPos center, StationType targetType) {
        // 搜索范围：XZ=20, Y=8（同结构尺寸）
        //  —— 之前用 XZ=48/Y=16 是错误的：48 格内完全可能存在 2 个「同类型不同站」的结构，
        //     扫描到「邻居站的 detector」之后把订单 startPos 设成邻居站的 detectorPos，
        //     随后玩家接单时 verifyStation 会通过（那个位置的方块确实存在且类型相同），
        //     但这个订单根本不属于「当前玩家面前打开的货运站A」→ 后续 generateCargo 失败或出现显示bug，
        //     用户还会看到「本不属于他已连接站」的订单，造成时而显示时而不显示。
        //  合理阈值：Create 货运站结构本体 3x3x9，加上 detector/生成器位置偏移，
        //     XZ±20、Y±8 的立方体完全足够覆盖单站所有附属方块，不可能错进邻居站 30~40 格开外。
        final int rangeXZ = 20;
        final int rangeY = 8;
        BlockPos.MutableBlockPos cursor = center.mutable();
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -rangeXZ; dx <= rangeXZ; dx++) {
            for (int dy = -rangeY; dy <= rangeY; dy++) {
                for (int dz = -rangeXZ; dz <= rangeXZ; dz++) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof CargoDetectorBlock)) continue;
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!(be instanceof CargoDetectorBlockEntity detector)) continue;
                    if (detector.getStationType() != targetType) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bestDist) {
                        bestDist = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        if (best != null && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[CargoDispatch] searchNearbyDetector 中心={} 类型={} → 最近detector={} distSqr={}",
                    center.toShortString(), targetType != null ? targetType.getId() : "?",
                    best.toShortString(), bestDist);
        }
        return best;
    }

    // =========================================================================
    // 提交页面相关处理器
    // =========================================================================

    /**
     * 处理客户端请求提交列表（服务端）
     * 原理：客户端切到提交 Tab 或每 2 秒主动请求，服务端调 sendSubmitList 回复
     */
    private static void handleRequestSubmitList(RequestSubmitListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // 静默：提交列表为打开 UI 后每 2 秒轮询，拒绝时不能刷聊天（页面内黄字提示）
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player, false)) return;
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            if (player.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5) <= 100) {
                sendSubmitList(context, stationPos);
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理请求提交列表失败", ex);
            return null;
        });
    }

    /**
     * 处理手动提交货箱请求（服务端）
     * 原理：
     *  - 通过 stationPos 找到 CargoStationBlockEntity（校验权限）
     *  - 通过 detectorPos 找到 CargoDetectorBlockEntity
     *  - 调 detector.forceSubmitCargo() 触发收货
     *  - 根据提交结果给操作玩家发「成功/失败」系统消息
     *  - 完成后重新下发 SyncSubmitListPayload 刷新 UI
     */
    private static void handleSubmitCargo(SubmitCargoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) return;
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            BlockPos detectorPos = new BlockPos(payload.detectorX(), payload.detectorY(), payload.detectorZ());
            ServerLevel serverLevel = player.serverLevel();

            // 校验 station 合法性（玩家必须在 10 格内）
            BlockEntity stationEntity = serverLevel.getBlockEntity(stationPos);
            if (!(stationEntity instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.station_not_found"));
                return;
            }
            if (player.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5) > 100) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.too_far"));
                return;
            }

            BlockEntity detectorBE = serverLevel.getBlockEntity(detectorPos);
            if (!(detectorBE instanceof CargoDetectorBlockEntity detector)) {
                LOGGER.warn("[CargoDispatch] 检测器不存在: {}", detectorPos);
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.failed_detector_gone"));
                sendSubmitList(context, stationPos);
                return;
            }
            // 归属校验：该检测器必须与当前货运站同组（编号/类型匹配 + 绑定半径内），
            // 防止玩家手工发包借邻居站检测器提交
            if (!com.hzldm.createcargodispatch.cargo.StationGroupHelper
                    .isDetectorBound(serverLevel, stationBE, detectorPos)) {
                LOGGER.warn("[CargoDispatch] 拦截跨站提交：检测器 {} 不属于站 {}", detectorPos, stationPos);
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.detector_not_linked"));
                sendSubmitList(context, stationPos);
                return;
            }

            // --- 在调用 forceSubmitCargo 之前先判断是否还有效 ---
            // 从 payload 中提取 cargoData / orderId 检查订单是否已经完成
            boolean validBefore = false;
            String orderIdBefore = null;
            if (payload.hasSubLevel()) {
                UUID uuid = payload.subLevelUuid();
                if (uuid != null && com.hzldm.createcargodispatch.cargo.CargoManager.isCargoSubLevel(uuid)) {
                    com.hzldm.createcargodispatch.cargo.CargoData data =
                            com.hzldm.createcargodispatch.cargo.CargoManager.getCargoDataBySubLevel(uuid);
                    if (data != null) {
                        orderIdBefore = data.getOrderId();
                        com.hzldm.createcargodispatch.cargo.OrderData order =
                                orderIdBefore != null ? com.hzldm.createcargodispatch.cargo.OrderManager.getOrder(orderIdBefore) : null;
                        validBefore = (order != null
                                && order.getStatus() != com.hzldm.createcargodispatch.cargo.OrderData.Status.COMPLETED
                                && order.getStatus() != com.hzldm.createcargodispatch.cargo.OrderData.Status.CANCELLED);
                    }
                }
            } else {
                BlockPos ctrl = new BlockPos(payload.controllerX(), payload.controllerY(), payload.controllerZ());
                if (serverLevel.isLoaded(ctrl)
                        && serverLevel.getBlockState(ctrl).getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock) {
                    com.hzldm.createcargodispatch.cargo.CargoData data = com.hzldm.createcargodispatch.cargo.CargoManager.query(ctrl);
                    if (data != null) {
                        orderIdBefore = data.getOrderId();
                        com.hzldm.createcargodispatch.cargo.OrderData order =
                                orderIdBefore != null ? com.hzldm.createcargodispatch.cargo.OrderManager.getOrder(orderIdBefore) : null;
                        validBefore = (order != null
                                && order.getStatus() != com.hzldm.createcargodispatch.cargo.OrderData.Status.COMPLETED
                                && order.getStatus() != com.hzldm.createcargodispatch.cargo.OrderData.Status.CANCELLED);
                    }
                }
            }

            // 目标站编号校验：货箱只能在订单指定编号的终点站提交
            // （检测器 isRouteMatch 是最终防线，这里提前给出明确错误提示）
            com.hzldm.createcargodispatch.cargo.CargoData targetCargo;
            BlockPos controllerPos = payload.hasSubLevel() ? null
                    : new BlockPos(payload.controllerX(), payload.controllerY(), payload.controllerZ());
            if (payload.hasSubLevel()) {
                targetCargo = com.hzldm.createcargodispatch.cargo.CargoManager
                        .getCargoDataBySubLevel(payload.subLevelUuid());
            } else {
                targetCargo = com.hzldm.createcargodispatch.cargo.CargoManager.query(controllerPos);
            }
            String expectTargetId = targetCargo != null ? targetCargo.getTargetStationId() : "";
            String myStationId = stationBE.getStationId();
            if (expectTargetId != null && !expectTargetId.isEmpty()
                    && myStationId != null && !myStationId.isEmpty()
                    && !expectTargetId.equals(myStationId)) {
                LOGGER.warn("[CargoDispatch] 拦截错站提交：货箱目标站编号 {} != 当前站 {}", expectTargetId, myStationId);
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.wrong_target_station"));
                sendSubmitList(context, stationPos);
                return;
            }

            detector.forceSubmitCargo(payload.subLevelUuid(), controllerPos, serverLevel);

            // --- 提交之后反馈 ---
            // 成功：订单变为 COMPLETED 或 已经找不到原货物（说明被正常收货流程消耗了）
            boolean success = false;
            String shortId = null;
            if (orderIdBefore != null && !orderIdBefore.isEmpty()) {
                shortId = orderIdBefore.length() > 8 ? orderIdBefore.substring(0, 8) : orderIdBefore;
                com.hzldm.createcargodispatch.cargo.OrderData order = com.hzldm.createcargodispatch.cargo.OrderManager.getOrder(orderIdBefore);
                success = (order == null)
                        || (order.getStatus() == com.hzldm.createcargodispatch.cargo.OrderData.Status.COMPLETED)
                        || (order.getStatus() == com.hzldm.createcargodispatch.cargo.OrderData.Status.CANCELLED);
            } else {
                // 没订单号的情况下：如果货物还在（没被收掉）就是失败
                if (payload.hasSubLevel()) {
                    success = !com.hzldm.createcargodispatch.cargo.CargoManager.isCargoSubLevel(payload.subLevelUuid());
                } else {
                    BlockPos ctrl = new BlockPos(payload.controllerX(), payload.controllerY(), payload.controllerZ());
                    success = !(serverLevel.isLoaded(ctrl)
                            && serverLevel.getBlockState(ctrl).getBlock() instanceof com.hzldm.createcargodispatch.block.CargoBlock);
                }
            }
            if (success) {
                // 消息已由 CargoDetectorBlockEntity.finalizeOrderCompletion 统一发送（一条汇总：订单号+货物数+奖励数+真实物品名）
                // 这里不再重复发 create_cargo_dispatch.submit.success / submit.success_no_order，避免三层重复信息。
            } else {
                // 失败：订单/货物还在，显示失败
                player.sendSystemMessage(Component.translatable(shortId != null
                        ? "create_cargo_dispatch.submit.failed_still_pending"
                        : "create_cargo_dispatch.submit.failed_already_done"));
            }

            // 完成后重新下发最新提交列表（提交了/失败了，状态已变，强制重扫不走缓存）
            if (context.player() instanceof ServerPlayer sp) {
                sendSubmitList(sp, stationPos, true, (pkt, pl) -> PacketDistributor.sendToPlayer(pl, pkt));
            }
        }).exceptionally(ex -> {
            LOGGER.error("处理手动提交货箱失败", ex);
            if (context.player() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.failed_internal"));
            }
            return null;
        });
    }

    /**
     * 处理切换自动提交开关请求（服务端）
     */
    private static void handleToggleAutoSubmit(ToggleAutoSubmitPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) return;
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            ServerLevel serverLevel = player.serverLevel();
            BlockEntity be = serverLevel.getBlockEntity(stationPos);
            if (!(be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.station_not_found"));
                return;
            }
            if (player.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5, stationPos.getZ() + 0.5) > 100) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.too_far"));
                return;
            }
            stationBE.setAutoSubmit(payload.autoSubmit());
            // 切换后立即回显（force=true 确保重扫最新，setAutoSubmit 里已 invalidate，这里双保险）
            sendSubmitList(player, stationPos, true, (pkt, pl) -> PacketDistributor.sendToPlayer(pl, pkt));
        }).exceptionally(ex -> {
            LOGGER.error("处理切换自动提交开关失败", ex);
            return null;
        });
    }

    /** 处理生成器出货模式设置（距离/方块归属服务端校验） */
    private static void handleSetGeneratorMode(SetGeneratorModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BlockPos generatorPos = new BlockPos(payload.x(), payload.y(), payload.z());
            ServerLevel serverLevel = player.serverLevel();
            BlockEntity be = serverLevel.getBlockEntity(generatorPos);
            if (!(be instanceof CargoGeneratorBlockEntity generator)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.generator.not_found"));
                return;
            }
            if (player.distanceToSqr(generatorPos.getX() + 0.5, generatorPos.getY() + 0.5,
                    generatorPos.getZ() + 0.5) > 100) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.too_far"));
                return;
            }
            generator.setSpawnMode(GeneratorSpawnMode.byName(payload.mode()));
        }).exceptionally(ex -> {
            LOGGER.error("处理生成器模式设置失败", ex);
            return null;
        });
    }

    /** 设置货运站新货箱尺寸偏好（只影响之后新货箱；距离校验防远程发包） */
    private static void handleSetCargoDimensions(SetCargoDimensionsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ServerLevel serverLevel = player.serverLevel();
            BlockPos stationPos = new BlockPos(payload.stationX(), payload.stationY(), payload.stationZ());
            if (!(serverLevel.getBlockEntity(stationPos)
                    instanceof CargoStationBlockEntity station)) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.generator.not_found"));
                return;
            }
            if (player.distanceToSqr(stationPos.getX() + 0.5, stationPos.getY() + 0.5,
                    stationPos.getZ() + 0.5) > 256) {
                player.sendSystemMessage(Component.translatable("create_cargo_dispatch.submit.too_far"));
                return;
            }
            CargoDimensions dims = new CargoDimensions(payload.sx(), payload.sy(), payload.sz());
            station.setConfiguredDimensions(dims);

            // 原地刷新本站 PENDING 订单（尺寸/数量/奖励），已接单的实物货箱与其他站订单均不受影响
            OrderManager.refreshPendingDimensions(
                    serverLevel, stationPos, station.getStationType(), dims);

            player.displayClientMessage(Component.translatable(
                    "create_cargo_dispatch.config.dims_applied", dims.toString()), true);
        }).exceptionally(ex -> {
            LOGGER.error("处理货箱尺寸设置失败", ex);
            return null;
        });
    }

    /**
     * 通用内部实现：扫描货箱 + 组装 SyncSubmitListPayload，然后通过 sender 回调发送
     *
     * @param forceRescan true=先强制使缓存失效后重扫（提交成功后 / 切换 auto 时需要看到最新），
     *                    false=默认走 2 秒缓存（UI 轮询刷新时用，减少重扫）
     * @param sender 如何把包发送到玩家：(pkt, pl) -> { context.reply(pkt) 或 PacketDistributor.sendToPlayer }
     */
    private static void sendSubmitList(ServerPlayer player, BlockPos stationPos,
                                       boolean forceRescan,
                                       java.util.function.BiConsumer<SyncSubmitListPayload, ServerPlayer> sender) {
        if (player == null || stationPos == null || sender == null) return;
        BlockEntity be = player.serverLevel().getBlockEntity(stationPos);
        if (!(be instanceof com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity stationBE)) return;

        if (forceRescan) {
            stationBE.invalidateSubmitListCache();
        }
        List<com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity.SubmittableCargo> rawList =
                stationBE.scanNearbySubmittableCargos();
        List<SyncSubmitListPayload.SubmitEntry> entries = new ArrayList<>(rawList.size());
        int idx = 0;
        for (com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity.SubmittableCargo c : rawList) {
            List<SyncSubmitListPayload.ItemSummary> items = new ArrayList<>(c.displayItems().size());
            for (net.minecraft.world.item.ItemStack is : c.displayItems()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(is.getItem()).toString();
                items.add(new SyncSubmitListPayload.ItemSummary(id, is.getCount()));
            }
            boolean hasSub = c.subLevelUuid() != null;
            UUID uuid = c.subLevelUuid();
            int cx = 0, cy = 0, cz = 0;
            if (c.controllerPos() != null) {
                cx = c.controllerPos().getX();
                cy = c.controllerPos().getY();
                cz = c.controllerPos().getZ();
            }
            entries.add(new SyncSubmitListPayload.SubmitEntry(
                    idx++, hasSub, uuid, cx, cy, cz,
                    c.detectorPos().getX(), c.detectorPos().getY(), c.detectorPos().getZ(),
                    c.totalItemCount(), c.orderId(), c.sourceStationTypeId(), items));
        }
        SyncSubmitListPayload resp = new SyncSubmitListPayload(
                stationPos.getX(), stationPos.getY(), stationPos.getZ(),
                entries, stationBE.isAutoSubmit(),
                stationBE.hasNearbyGenerator(), stationBE.hasNearbyDetector());
        sender.accept(resp, player);
    }

    /** 旧 3 参兼容：context.reply 通道（客户端请求刷新）→ 不强制重扫，走缓存（节省开销，2 秒内直接返回） */
    private static void sendSubmitList(IPayloadContext context, BlockPos stationPos) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        sendSubmitList(player, stationPos, false, (pkt, pl) -> context.reply(pkt));
    }

    /** 2 参主动推送：CargoGeneratorMenu 打开时立即推 → 第一次缓存为 null，自然会重扫 */
    public static void sendSubmitList(ServerPlayer player, BlockPos stationPos) {
        sendSubmitList(player, stationPos, false, (pkt, pl) ->
                PacketDistributor.sendToPlayer(pl, pkt));
    }

    // =========================================================================
    // 背包「已连接站点 → 📋 订单按钮」远程查看站订单相关处理器
    // =========================================================================

    /**
     * 处理客户端请求查看某货运站的未接订单（服务端）
     *
     * 原理：
     *  - 安全校验（防止未授权偷窥）：玩家必须真的「已连接」该站点（LinkageManager.getConnectedStations 查坐标命中）
     *    未连接直接静默丢弃，不回包不报错，避免恶意玩家枚举坐标探测其他玩家订单
     *  - 订单过滤：
     *    1) 仅保留 PENDING 状态
     *    2) 源站类型 == 请求站类型（与货运站 UI 显示规则一致，玩家连接了伐木场就只看伐木场作为起站的未接订单）
     *    3) 玩家可见性过滤（复用 OrderManager.isOrderVisibleToPlayer：玩家是否「连接了订单终站类型」）
     *  - 回包：SyncStationOrdersViewerPayload，附带站元信息 + 下次刷新游戏时间（UI 倒计时用）
     */
    private static void handleRequestStationOrders(RequestStationOrdersPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // 静默：远程查看为页面轮询，拒绝时不刷聊天（页面内黄字提示）
            if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player, false)) return;
            BlockPos reqPos = new BlockPos(payload.sourceX(), payload.sourceY(), payload.sourceZ());
            ServerLevel serverLevel = player.serverLevel();
            LinkageManager linkageMgr = LinkageManager.get(serverLevel);

            // 1) 连接状态校验：查玩家已连接站点列表中是否存在同坐标条目
            boolean connected = false;
            for (LinkageManager.ConnectedStation s : linkageMgr.getConnectedStations(player.getUUID())) {
                if (reqPos.equals(s.pos())) { connected = true; break; }
            }
            if (!connected) {
                LOGGER.warn("[CargoDispatch] 拦截未授权查看站订单：玩家{} 未连接站点 {}，丢弃请求",
                        player.getUUID(), reqPos);
                return;
            }

            // 2) 按类型筛选订单 + 玩家可见性过滤（与货运站 UI 接单 Tab 使用同一套可见性规则）
            StationType reqType = StationType.byId(payload.sourceType());
            List<OrderData> pendingOfType = OrderManager.getPendingOrders(reqType);
            List<SyncOrdersPayload.OrderEntry> entries = new ArrayList<>(pendingOfType.size());
            for (OrderData order : pendingOfType) {
                if (!OrderManager.isOrderVisibleToPlayer(serverLevel, player.getUUID(), order)) continue;
                entries.add(SyncOrdersPayload.OrderEntry.from(order));
            }
            long nextRefresh = OrderManager.getLastRefreshTime() + OrderManager.getNextRefreshInterval();

            // 3) 回包（只有验证通过的玩家才会收到，客户端收到后就可以安全打开 StationOrdersViewerScreen）
            context.reply(new SyncStationOrdersViewerPayload(
                    payload.sourceX(), payload.sourceY(), payload.sourceZ(),
                    payload.sourceType() != null ? payload.sourceType() : "",
                    entries, nextRefresh));
            LOGGER.debug("[CargoDispatch] 响应远程查看订单：玩家{} 站{}({}) 返回{}条未接订单",
                    player.getName().getString(), reqPos, payload.sourceType(), entries.size());
        }).exceptionally(ex -> {
            LOGGER.error("处理请求查看站订单失败", ex);
            return null;
        });
    }

    // =========================================================================
    // 调试货箱编辑页（不可放置的调试物品右键打开，编辑主手物品组件）
    // =========================================================================

    /** 构建并下发调试货箱编辑数据（字段 + 玩家公司可选目标站） */
    public static void sendDebugCargoSync(ServerLevel level, ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof com.hzldm.createcargodispatch.item.DebugCargoItem)) return;
        String orderId = com.hzldm.createcargodispatch.item.DebugCargoItem.getOrderId(stack);
        StationType sourceType = StationType.byId(
                com.hzldm.createcargodispatch.item.DebugCargoItem.getSourceTypeId(stack));
        String sourceTypeId = sourceType.getId();
        // 货物选择规范化：未选或不属于当前类型池 → 回退池首项并写回物品
        String cargoItemId = normalizeCargoItem(stack, sourceType);
        java.util.List<SyncDebugCargoPayload.TargetEntry> targets =
                collectDebugTargets(level, player, sourceTypeId);
        int selected = -1;
        if (com.hzldm.createcargodispatch.item.DebugCargoItem.hasTarget(stack)) {
            BlockPos tp = com.hzldm.createcargodispatch.item.DebugCargoItem.getTargetPos(stack);
            for (int i = 0; i < targets.size(); i++) {
                SyncDebugCargoPayload.TargetEntry t = targets.get(i);
                if (t.x() == tp.getX() && t.y() == tp.getY() && t.z() == tp.getZ()) {
                    selected = i;
                    break;
                }
            }
        }
        CargoDimensions dims = com.hzldm.createcargodispatch.item.DebugCargoItem.getDimensions(stack);
        PacketDistributor.sendToPlayer(player,
                new SyncDebugCargoPayload(orderId, sourceTypeId, cargoItemId,
                        dims.width(), dims.height(), dims.length(), selected, targets));
    }

    /** 确保物品记录的货物属于全类型联合货物池，无效时回退池首项并写回，返回有效物品 id */
    private static String normalizeCargoItem(
            ItemStack stack, com.hzldm.createcargodispatch.cargo.StationType sourceType) {
        String current = com.hzldm.createcargodispatch.item.DebugCargoItem.getCargoItemId(stack);
        java.util.List<Item> pool = StationType.getAllCargoPool();
        for (Item item : pool) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString().equals(current)) {
                return current;
            }
        }
        String first = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(pool.get(0)).toString();
        com.hzldm.createcargodispatch.item.DebugCargoItem.writeFields(stack, null, null, first);
        return first;
    }

    /** 目标站候选：玩家公司已连接站点，排除与货物源类型相同的站（同类型不能作为目的地） */
    private static java.util.List<SyncDebugCargoPayload.TargetEntry> collectDebugTargets(
            ServerLevel level, ServerPlayer player, String sourceTypeId) {
        java.util.List<SyncDebugCargoPayload.TargetEntry> targets = new java.util.ArrayList<>();
        for (LinkageManager.ConnectedStation s : LinkageManager.get(level).getConnectedStations(player.getUUID())) {
            if (s.type() == null || s.type() == StationType.GENERIC) continue;
            if (s.type().getId().equals(sourceTypeId)) continue;
            String stationId = "";
            if (level.getBlockEntity(s.pos())
                    instanceof com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity detector) {
                stationId = detector.getStationId() == null ? "" : detector.getStationId();
            }
            targets.add(new SyncDebugCargoPayload.TargetEntry(
                    s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), stationId));
        }
        return targets;
    }

    /** 选择目标站（写主手调试物品组件） */
    private static void handleUpdateDebugCargoTarget(UpdateDebugCargoTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isCreative()) return;
            ServerLevel level = player.serverLevel();
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof com.hzldm.createcargodispatch.item.DebugCargoItem)) return;
            String sourceTypeId = com.hzldm.createcargodispatch.item.DebugCargoItem.getSourceTypeId(stack);
            java.util.List<SyncDebugCargoPayload.TargetEntry> targets =
                    collectDebugTargets(level, player, sourceTypeId);
            int index = payload.targetIndex();
            com.hzldm.createcargodispatch.item.DebugCargoItem.writeTarget(
                    stack, (index < 0 || index >= targets.size()) ? null : targets.get(index));
            sendDebugCargoSync(level, player);
        }).exceptionally(ex -> {
            LOGGER.error("调试货箱目标站更新失败", ex);
            return null;
        });
    }

    /** 字段更新（订单号 / 源类型 / 货物物品；源类型变化时目标失效自动清空、货物重置为池首项） */
    private static void handleUpdateDebugCargoFields(UpdateDebugCargoFieldsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isCreative()) return;
            ServerLevel level = player.serverLevel();
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof com.hzldm.createcargodispatch.item.DebugCargoItem)) return;
            if (payload.sourceTypeId() != null) {
                StationType newSource = StationType.byId(payload.sourceTypeId());
                if (com.hzldm.createcargodispatch.item.DebugCargoItem.hasTarget(stack)
                        && newSource.getId().equals(
                                com.hzldm.createcargodispatch.item.DebugCargoItem.getTargetTypeId(stack))) {
                    com.hzldm.createcargodispatch.item.DebugCargoItem.writeTarget(stack, null);
                }
                // 源类型独立变化，货物选择保持（货物允许在全类型联合池中任意选择）
                String cargoItemId = payload.cargoItemId();
                com.hzldm.createcargodispatch.item.DebugCargoItem
                        .writeFields(stack, null, newSource.getId(), cargoItemId);
            } else if (payload.cargoItemId() != null) {
                // 仅允许选择全类型联合池内物品，防止手工发包伪造
                String id = payload.cargoItemId();
                boolean allowed = StationType.getAllCargoPool().stream().anyMatch(it ->
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString().equals(id));
                String safeId = allowed ? id
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(StationType.getAllCargoPool().get(0)).toString();
                com.hzldm.createcargodispatch.item.DebugCargoItem.writeFields(
                        stack, null, null, safeId);
            }
            if (payload.orderId() != null) {
                com.hzldm.createcargodispatch.item.DebugCargoItem
                        .writeFields(stack, payload.orderId(), null, null);
            }
            sendDebugCargoSync(level, player);
        }).exceptionally(ex -> {
            LOGGER.error("调试货箱字段更新失败", ex);
            return null;
        });
    }

    /** 更新调试货箱尺寸（写主手调试物品） */
    private static void handleUpdateDebugCargoDims(UpdateDebugCargoDimsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isCreative()) return;
            ServerLevel level = player.serverLevel();
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof com.hzldm.createcargodispatch.item.DebugCargoItem)) return;
            CargoDimensions dims = new CargoDimensions(payload.w(), payload.h(), payload.l());
            com.hzldm.createcargodispatch.item.DebugCargoItem.writeDimensions(stack, dims);
            sendDebugCargoSync(level, player);
        }).exceptionally(ex -> {
            LOGGER.error("调试货箱尺寸更新失败", ex);
            return null;
        });
    }

    /** 生成货箱：把主手调试物品变为按类型自动填充货物的可放置 CargoBlockItem */
    private static void handleApplyDebugCargo(ApplyDebugCargoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isCreative()) return;
            ServerLevel level = player.serverLevel();
            ItemStack debugStack = player.getMainHandItem();
            if (!(debugStack.getItem() instanceof com.hzldm.createcargodispatch.item.DebugCargoItem item)) return;

            ItemStack blockStack = new ItemStack(com.hzldm.createcargodispatch.registry.ModBlocks.CARGO.get());
            net.minecraft.nbt.CompoundTag beTag = new net.minecraft.nbt.CompoundTag();

            // 货箱尺寸 + 容量（尺寸来自调试物品，数量按 CargoBalance：矿山类减半、整体已下调）
            StationType sourceType = StationType.byId(item.getSourceTypeId(debugStack));
            CargoDimensions dims = com.hzldm.createcargodispatch.item.DebugCargoItem
                    .getDimensions(debugStack);
            String cargoItemId = normalizeCargoItem(debugStack, sourceType);
            Item cargoItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse(cargoItemId));
            int count = CargoBalance.defaultItems(sourceType, dims, cargoItem);
            // 槽位数按该物品实际堆叠给出（鸡蛋等低堆叠物品不能按 64 算，否则照样溢出）
            int stackMax = cargoItem != null ? cargoItem.getDefaultMaxStackSize() : 64;
            int slots = Math.max(1, (count / stackMax) + 1);
            net.minecraft.world.SimpleContainer tmp = new net.minecraft.world.SimpleContainer(slots);
            if (cargoItem != null) {
                net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(
                        new net.neoforged.neoforge.items.wrapper.InvWrapper(tmp),
                        new ItemStack(cargoItem, count), false);
            }
            beTag.put("Inventory", tmp.createTag(level.registryAccess()));

            // CargoData（含尺寸）
            com.hzldm.createcargodispatch.cargo.CargoData data = new com.hzldm.createcargodispatch.cargo.CargoData();
            String orderId = item.getOrderId(debugStack);
            if (orderId == null || orderId.isEmpty()) {
                orderId = java.util.UUID.randomUUID().toString().substring(0, 8);
            }
            data.setOrderId(orderId);
            data.setSourceStationType(sourceType.getId());
            data.setDimensions(dims);
            if (item.hasTarget(debugStack)) {
                data.setTargetPos(item.getTargetPos(debugStack));
                data.setTargetDimension(level.dimension().location());
                data.setTargetStationType(item.getTargetTypeId(debugStack));
                data.setTargetStationId(item.getTargetStationId(debugStack));
            }
            beTag.put("CargoData", data.save(level.registryAccess()));
            beTag.putBoolean("IsMainBlock", true);
            // BLOCK_ENTITY_DATA 组件序列化/放置时强制要求 "id" 字段，缺失会导致背包保存直接崩溃
            beTag.putString("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(com.hzldm.createcargodispatch.registry.ModBlockEntities.CARGO.get()).toString());
            blockStack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                    net.minecraft.world.item.component.CustomData.of(beTag));
            // 命名：通用→普通货箱；具体类型直接复用对应货箱方块的语言键（伐木场货箱/矿场货箱…），
            // 避免用「站名(伐木场货物)+货箱」拼出「伐木场货物货箱」双重后缀
            Component displayName = sourceType == StationType.GENERIC
                    ? Component.translatable("create_cargo_dispatch.debug_cargo.name_generic")
                    : Component.translatable(
                            "block.create_cargo_dispatch." + sourceType.getId() + "_cargo");
            blockStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, displayName);

            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, blockStack);
            player.closeContainer();
            player.displayClientMessage(Component.translatable(
                    "create_cargo_dispatch.debug_cargo.applied"), true);
        }).exceptionally(ex -> {
            LOGGER.error("生成调试货箱失败", ex);
            return null;
        });
    }

    // =========================================================================
    // 通用货运方块「类型转换」（客户端 StationTypeSelectScreen 选择后调用）
    // =========================================================================

    /**
     * 处理「转换通用货运物品」请求（服务端）
     * 原理：
     *  - 客户端选择页点击某类型后发送 ConvertStationItemPayload
     *  - 服务端校验：主手物品必须是对应类别的「通用」物品（cargo_station/cargo_generator/cargo_detector），
     *    防止玩家手工发包把任意物品乱转换
     *  - 校验通过后把主手 ItemStack 替换为对应类型的专属 BlockItem（保留数量）
     */
    private static void handleConvertStationItem(ConvertStationItemPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SelectableStationItem.ItemKind kind;
            try {
                kind = SelectableStationItem.ItemKind.valueOf(payload.kind());
            } catch (IllegalArgumentException e) {
                return; // 非法类别，丢弃
            }
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty() || !isGenericStationItem(held.getItem(), kind)) return; // 防手发包
            StationType type = StationType.byId(payload.stationTypeId());
            if (type == StationType.GENERIC) return; // 不允许转换回通用
            Item target = StationItemConverter.resolve(kind, type);
            if (target == null || target == held.getItem()) return;
            // 替换主手物品（保留数量），客户端通过 setItem 自动同步
            player.getInventory().setItem(player.getInventory().selected, new ItemStack(target, held.getCount()));
            player.sendSystemMessage(Component.translatable("create_cargo_dispatch.convert.done",
                    Component.translatable(type.getTranslationKey())));
            LOGGER.info("[CargoDispatch] 玩家 {} 将 {} 转换为 {}", player.getName().getString(),
                    held.getItem().getDescriptionId(), type.getId());
        }).exceptionally(ex -> {
            LOGGER.error("处理转换货运物品失败", ex);
            return null;
        });
    }

    /** 校验主手物品是否是对应类别的「通用」物品 */
    private static boolean isGenericStationItem(Item item, SelectableStationItem.ItemKind kind) {
        return switch (kind) {
            case STATION -> item == com.hzldm.createcargodispatch.registry.ModItems.CARGO_STATION.get();
            case GENERATOR -> item == com.hzldm.createcargodispatch.registry.ModItems.CARGO_GENERATOR.get();
            case DETECTOR -> item == com.hzldm.createcargodispatch.registry.ModItems.CARGO_DETECTOR.get();
        };
    }
}
