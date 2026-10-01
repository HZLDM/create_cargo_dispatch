package com.hzldm.createcargodispatch.command;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.blockentity.CargoDetectorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoGeneratorBlockEntity;
import com.hzldm.createcargodispatch.blockentity.CargoStationBlockEntity;
import com.hzldm.createcargodispatch.cargo.OrderData;
import com.hzldm.createcargodispatch.cargo.OrderManager;
import com.hzldm.createcargodispatch.cargo.StationType;
import com.hzldm.createcargodispatch.network.AddWaypointPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 模组指令注册
 *
 * 原理：
 *  - RegisterCommandsEvent 是 NeoForge 游戏事件（非 IModBusEvent）
 *  - 必须注册到 GAME bus，不能注册到 modEventBus
 *  - 使用 @EventBusSubscriber 自动注册到 GAME bus
 *  - 使用 literal 创建 /createcargodispatch 根指令
 *
 * 子指令：
 *  - refreshorders：强制刷新所有待接单订单（需 OP 等级 2）
 *  - generateorder：为玩家正对着的货运站生成 1 个随机订单（需 OP 等级 2）
 *  - addwaypoint：添加 Xaero 路径点（所有玩家可用，由聊天栏点击事件触发）
 *  - clearOrdersTargeted：删除玩家正对着的货运站（/生成器/检测器）结构内的所有订单（PENDING 移除 + ACCEPTED 取消并删货箱）（需 OP 等级 2）
 *  - clearAllOrders：一键清空全服所有订单（PENDING 清空 + ACCEPTED 取消并删货箱）（需 OP 等级 2）
 */
@EventBusSubscriber(modid = CreateCargoDispatch.MODID)
public final class ModCommands {

    private ModCommands() {
    }

    /**
     * 监听指令注册事件
     * 原理：@EventBusSubscriber 自动将此方法注册到 GAME bus
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("createcargodispatch")
                        // 根指令不设权限限制，各子指令单独设权限
                        .then(Commands.literal("refreshorders")
                                .requires(src -> src.hasPermission(2)) // OP 权限等级 2
                                .executes(ModCommands::onRefreshOrders)
                        )
                        .then(Commands.literal("generateorder")
                                .requires(src -> src.hasPermission(2)) // OP 权限等级 2
                                .executes(ModCommands::onGenerateOrder)
                        )
                        .then(Commands.literal("clearOrdersTargeted")
                                .requires(src -> src.hasPermission(2)) // OP 权限等级 2
                                .executes(ModCommands::onClearOrdersTargeted)
                        )
                        .then(Commands.literal("clearAllOrders")
                                .requires(src -> src.hasPermission(2)) // OP 权限等级 2
                                .executes(ModCommands::onClearAllOrders)
                        )
                        .then(Commands.literal("addwaypoint")
                                .requires(src -> src.hasPermission(0)) // 所有玩家可用
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("dim", StringArgumentType.string())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ModCommands::onAddWaypoint))))))
                        )
                        // 邀请消息「点击加入」入口：所有玩家可用，UUID 由服务端生成，非法值静默拒绝
                        .then(Commands.literal("joincompany")
                                .requires(src -> src.hasPermission(0))
                                .then(Commands.argument("uuid", StringArgumentType.string())
                                        .executes(ModCommands::onJoinCompany)))
        );
    }

    /**
     * /createcargodispatch joincompany <uuid>：联合运输邀请一键加入
     * 原理：聊天邀请消息的 ClickEvent.RUN_COMMAND 触发；UUID 非法时静默失败防伪造刷屏
     */
    private static int onJoinCompany(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            UUID companyId = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
            com.hzldm.createcargodispatch.company.CompanyService.joinCompany(player, companyId);
            return 1;
        } catch (IllegalArgumentException | CommandSyntaxException e) {
            return 0;
        }
    }

    /**
     * /createcargodispatch refreshorders：强制刷新所有待接单订单
     * 原理：
     *  - 清除所有待接单订单
     *  - 重置 5 分钟刷新倒计时
     *  - 为所有在线玩家重新生成订单
     *  - 已接单订单不受影响
     */
    private static int onRefreshOrders(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        int generated = OrderManager.forceRefresh(level);
        src.sendSuccess(() -> Component.translatable(
                "create_cargo_dispatch.command.refresh_orders_success", generated), true);
        return generated;
    }

    /**
     * /createcargodispatch generateorder：为玩家正对着的货运站生成 1 个随机订单
     * 原理：
     *  - 使用 ClipContext 对玩家视线做 RayTrace（20 格距离）
     *  - 命中的方块 BE 是 CargoStation（货运站 / CargoGenerator（生成器）/ CargoDetector（检测器）任一即可，取出 stationType
     *  - 调用 OrderManager.generateOrderForStation，复用现有订单生成逻辑
     *  - 成功后打印订单号 / 货物 / 目标站信息
     */
    private static int onGenerateOrder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();

        // 公司门槛：无公司玩家造单无人可接（会成死单），指令同样拒绝
        if (!com.hzldm.createcargodispatch.company.CompanyService.requireCompanyMembership(player)) {
            return 0;
        }

        // 1. 玩家视线 RayTrace（20 格距离，不包含流体，阻挡模式为方块）
        BlockHitResult blockHit = rayTraceBlock(player, 20.0D);
        if (blockHit == null || blockHit.getType() != HitResult.Type.BLOCK) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.no_block"));
            return 0;
        }
        BlockPos hitPos = blockHit.getBlockPos();

        // 2. 检查被命中方块 BE，获取 stationType（三种 BE 类都有 getStationType()）
        BlockEntity be = level.getBlockEntity(hitPos);
        StationType sourceType = null;
        if (be instanceof CargoStationBlockEntity stationBE) {
            sourceType = stationBE.getStationType();
        } else if (be instanceof CargoGeneratorBlockEntity genBE) {
            sourceType = genBE.getStationType();
        } else if (be instanceof CargoDetectorBlockEntity detBE) {
            sourceType = detBE.getStationType();
        }
        if (sourceType == null) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.not_station"));
            return 0;
        }
        if (sourceType == StationType.GENERIC) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.bad_type"));
            return 0;
        }

        // 3. 调用订单生成逻辑（复用 generateOrderForStation）
        OrderData order = OrderManager.generateOrderForStation(
                level, player.getUUID(), hitPos, sourceType);
        if (order == null) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.failed"));
            return 0;
        }

        // 4. 成功：返回给执行器显示生成信息（货物 / 目标 / 奖励 / 订单号）
        //    OrderData 只有 getCargoItemId()（ResourceLocation 字符串，如 "minecraft:wheat"）
        //    通过 BuiltInRegistries.ITEM.get 反查 Item，拿不到则兜底显示原始 ID
        String rawId = order.getCargoItemId();
        Component cargoName;
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.parse(rawId);
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            cargoName = item != null
                    ? item.getDescription()
                    : Component.literal(rawId);
        } catch (Exception e) {
            cargoName = Component.literal(rawId);
        }
        // 指令查询/生成订单的显示统一用「短名起点→短名终点」，去掉"货物"二字，避免过长
        String routeShort = net.minecraft.network.chat.Component.translatable(order.getStationType().getShortTranslationKey()).getString()
                + " → "
                + net.minecraft.network.chat.Component.translatable(order.getTargetStationType().getShortTranslationKey()).getString();
        Component routeComp = Component.literal(routeShort);
        BlockPos targetPos = order.getTargetPos();
        Component targetPosComp = targetPos != null
                ? Component.literal(String.format("(%d,%d,%d)",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ()))
                : Component.literal("-");
        String shortId = order.getOrderId().length() > 8
                ? order.getOrderId().substring(0, 8)
                : order.getOrderId();

        Component successMsg = Component.translatable(
                        "create_cargo_dispatch.command.generateorder.success",
                        shortId,
                        cargoName,
                        order.getCargoCount(),
                        routeComp,
                        targetPosComp,
                        order.getReward())
                .withStyle(ChatFormatting.GREEN);
        src.sendSuccess(() -> successMsg, true);
        return 1;
    }

    /**
     * 对玩家做方块 RayTrace（ClipContext.Block.OUTLINE，不穿过液体）
     * 原理：复用原版 Player.pick() 相同的实现，将其转换为 ServerPlayer 的 BlockHitResult
     */
    private static BlockHitResult rayTraceBlock(Player player, double distance) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = player.getViewVector(1.0F);
        Vec3 end = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);
        ClipContext ctx = new ClipContext(
                eyePos, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        HitResult hit = player.level().clip(ctx);
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit;
        }
        return null;
    }

    /**
     * /createcargodispatch addwaypoint x y z dim name：添加 Xaero 路径点
     * 原理：
     *  - 由聊天栏点击事件触发（点击订单起点位置文本）
     *  - 发送 AddWaypointPayload 给执行者，客户端添加路径点
     *  - dim 含冒号需在命令中用引号包裹（StringArgumentType.string 支持引号解析）
     *  - name 为最后一个参数使用 greedyString，支持任意字符
     */
    private static int onAddWaypoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        String dim = StringArgumentType.getString(ctx, "dim");
        String name = StringArgumentType.getString(ctx, "name");

        ServerPlayer player = src.getPlayerOrException();
        // 发送路径点添加包给客户端，initials 用 "起" 标识起点
        AddWaypointPayload payload = new AddWaypointPayload(x, y, z, dim, name, "起");
        PacketDistributor.sendToPlayer(player, payload);
        return 1;
    }

    /**
     * /createcargodispatch clearOrdersTargeted：删除玩家正对着的货运站（/生成器/检测器）结构内的全部订单。
     * 规则：
     *  - 先做 RayTrace，命中的方块必须是三类 BE 之一（否则报错 not_station）
     *  - 调用 OrderManager.clearOrdersAtStationBlock：按「位置相等 或 (类型相同 且 XZ<=48 且 Y<=16)」匹配
     *    —— 匹配到的 PENDING 直接移除；ACCEPTED 取消订单并销毁货箱 SubLevel/静态货箱
     *  - 返回 PENDING 删除数 + ACCEPTED 取消数合计
     */
    private static int onClearOrdersTargeted(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();

        BlockHitResult blockHit = rayTraceBlock(player, 20.0D);
        if (blockHit == null || blockHit.getType() != HitResult.Type.BLOCK) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.no_block"));
            return 0;
        }
        BlockPos hitPos = blockHit.getBlockPos();
        StationTypeHit hit = extractStationType(level, hitPos);
        if (hit == null) {
            src.sendFailure(Component.translatable("create_cargo_dispatch.command.generateorder.not_station"));
            return 0;
        }
        int[] res = OrderManager.clearOrdersAtStationBlock(level, hitPos, hit.type());
        int removedPending = res[0];
        int cancelledAccepted = res[1];
        Component msg = Component.translatable(
                        "create_cargo_dispatch.command.clear_orders_targeted.success",
                        removedPending, cancelledAccepted,
                        hit.type() != null ? hit.type().getId() : "?",
                        hitPos.getX(), hitPos.getY(), hitPos.getZ())
                .withStyle(ChatFormatting.GOLD);
        src.sendSuccess(() -> msg, true);
        return Math.max(0, removedPending) + Math.max(0, cancelledAccepted);
    }

    /**
     * /createcargodispatch clearAllOrders：一键清空全服所有订单。
     * 规则：
     *  - PENDING_ORDERS 直接清空
     *  - ACCEPTED_ORDERS 遍历：逐一取消订单 + 调用 removeCargoBlocks 销毁货箱 SubLevel/静态货箱
     *  - 清除完成后 markDirty + broadcastPendingOrdersToAllPlayers 让客户端立刻同步
     */
    private static int onClearAllOrders(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        int[] res = OrderManager.clearAllOrders(level);
        int pendingCleared = res[0];
        int acceptedCancelled = res[1];
        Component msg = Component.translatable(
                        "create_cargo_dispatch.command.clear_all_orders.success",
                        pendingCleared, acceptedCancelled)
                .withStyle(ChatFormatting.DARK_RED);
        src.sendSuccess(() -> msg, true);
        return Math.max(0, pendingCleared) + Math.max(0, acceptedCancelled);
    }

    /**
     * 从指定位置的 BlockEntity 提取 stationType（三种 BE：CargoStation/CargoGenerator/CargoDetector）。
     * 用 record 同时返回 hitPos（方便将来扩展，如果有需要换成 detector 位置）和 stationType。
     */
    private record StationTypeHit(BlockPos pos, StationType type) { }

    private static StationTypeHit extractStationType(ServerLevel level, BlockPos hitPos) {
        if (level == null || hitPos == null) return null;
        BlockEntity be = level.getBlockEntity(hitPos);
        StationType type = null;
        if (be instanceof CargoStationBlockEntity stationBE) {
            type = stationBE.getStationType();
        } else if (be instanceof CargoGeneratorBlockEntity genBE) {
            type = genBE.getStationType();
        } else if (be instanceof CargoDetectorBlockEntity detBE) {
            type = detBE.getStationType();
        }
        if (type == null) return null;
        return new StationTypeHit(hitPos, type);
    }
}
