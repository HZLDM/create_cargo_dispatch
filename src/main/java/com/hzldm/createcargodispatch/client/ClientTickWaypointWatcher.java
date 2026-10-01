package com.hzldm.createcargodispatch.client;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户端 Tick 监听器：检查玩家是否到达路径点附近，并自动删除
 *
 * 原理：
 *  - 订阅 ClientTickEvent.Post（NeoForge 客户端 Tick 后事件）
 *  - 仅客户端（Dist.CLIENT）
 *  - 使用 @EventBusSubscriber(bus = Bus.GAME, value = Dist.CLIENT) 注册到 GAME bus
 *  - 每 20 tick（1 秒）扫描一次 ClientCargoCache.TRACKED_WAYPOINTS
 *  - 对于维度匹配的路径点：计算玩家→路径点平方距离 < 配置半径² → 自动删除
 *
 * 为什么用扫描而不是事件：
 *  - 路径点添加可以来自服务端 AddWaypointPayload 或玩家点击命令，统一入口在 ClientCargoCache.addWaypoint
 *  - 到达判定需要持续的位置比对，Tick 扫描是最稳妥的方案
 *  - 20 tick 间隔（1Hz）对 CPU 几乎零开销，最坏情况 1 秒延迟才自动删除，体验完全可接受
 *
 * 【9合1 配方缓存已从此处移除】：
 *  之前的「每秒 tick 检查 + 30 分钟强制刷新」有周期性 CPU 开销，已改为懒加载：
 *  - 客户端第一次调用 getItemMass / getNineToOneBlock（即第一次用护目镜看有普通物品的货箱时）自动懒构建
 *  - 之后若配方同步更新，服务端数据包同步完毕后下次 getItemMass 再懒重建
 *  - 真正的零周期性开销，不再每秒都做检查判断
 */
@SuppressWarnings("removal")  // NeoForge 21.1.244 中 Bus.GAME 标记过时但仍需使用
@EventBusSubscriber(modid = CreateCargoDispatch.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientTickWaypointWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Client");

    /** 多久扫描一次（tick），20 tick = 1 秒，避免每帧比对浪费 CPU */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** 自增计数器，达到 SCAN_INTERVAL_TICKS 时归零并执行扫描 */
    private static int tickCounter = 0;

    private ClientTickWaypointWatcher() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        // 1. 必要前置条件检查
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.level() == null) {
            return;
        }
        if (ClientCargoCache.TRACKED_WAYPOINTS.isEmpty()) {
            return;
        }
        // 2. 读取配置（CLIENT_CONFIG 在客户端可用）
        final boolean autoRemove;
        final int removeRadius;
        try {
            autoRemove = ModConfig.isWaypointAutoRemoveEnabled();
            removeRadius = ModConfig.getWaypointRemoveRadius();
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] 读取路径点自动删除配置失败，使用默认值(true,16)：{}", t.getMessage());
            return;
        }
        final double radiusSq = (double) removeRadius * removeRadius;
        final ResourceLocation playerDim = player.level().dimension().location();
        final BlockPos playerPos = player.blockPosition();

        // 3. 预构建活跃订单ID集合（含完整ID + 短号8位，一次构建避免在循环里反复查）
        java.util.Set<String> activeOrderIds = new java.util.HashSet<>();
        for (com.hzldm.createcargodispatch.network.SyncActiveOrdersPayload.ActiveOrderEntry e :
                ClientCargoCache.getActiveOrders()) {
            String id = e.orderId();
            activeOrderIds.add(id);
            if (id.length() > 8) activeOrderIds.add(id.substring(0, 8));
        }

        // 4. 收集需要删除的路径点名称（遍历时不修改 Map，避免 ConcurrentModificationException）
        List<String> toRemove = new ArrayList<>(4);
        for (Map.Entry<String, ClientCargoCache.TrackedWaypoint> entry :
                ClientCargoCache.TRACKED_WAYPOINTS.entrySet()) {
            String wpName = entry.getKey();
            if (wpName == null) continue;

            // ========== A) 订单状态兜底删除 ==========
            // 从路径点名字里提取 orderId 候选（# 号之后，或直接如果是纯 hex）
            String orderIdFromName = extractOrderIdFromWaypointName(wpName);
            if (orderIdFromName != null) {
                boolean idStillActive = false;
                String shortFromName = orderIdFromName.length() > 8
                        ? orderIdFromName.substring(0, 8) : orderIdFromName;
                for (String activeId : activeOrderIds) {
                    if (activeId.equals(orderIdFromName) || activeId.equals(shortFromName)
                            || orderIdFromName.contains(activeId) || activeId.contains(shortFromName)) {
                        idStillActive = true;
                        break;
                    }
                }
                if (!idStillActive && !activeOrderIds.isEmpty()) {
                    // 订单已不在活跃列表 → 删除（服务端可能漏通知或玩家没到达起点）
                    LOGGER.info("[CargoDispatch] 订单已失效（不在活跃订单集合），自动清理路径点: {}", wpName);
                    toRemove.add(wpName);
                    continue;  // 已决定删，不再做距离判定
                }
            }

            // ========== B) 距离到达自动删除 ==========
            if (!autoRemove) continue;
            // —— 关键规则：
            //  1) 「货运订单 #」前缀的路径点（目标货运站/检测器方向）：
            //     必须在 提交货物成功(finalizeOrderCompletion)后服务端主动发 RemoveWaypointPayload 删除
            //     （玩家走到目标站附近时还没把货箱送到检测器提交，此时不能提前删）
            //  2) 其他路径点（如聊天栏/命令生成的接货起点路径点等）：依旧遵循「到达半径内删除」
            if (wpName.startsWith("货运订单 #")) {
                continue;
            }
            ClientCargoCache.TrackedWaypoint wp = entry.getValue();
            // 维度不同：跳过（跨维度到达判定暂不支持，跨维度传送一般会有切换过程）
            if (!wp.dimension().equals(playerDim)) {
                continue;
            }
            // 距离判定：distSqr 返回 double，直接比较避免 long 的精度损失
            double distSq = playerPos.distSqr(wp.pos());
            if (distSq <= radiusSq) {
                LOGGER.info("[CargoDispatch] 玩家进入路径点半径 {}（实际距离²={}），自动删除: {}",
                        removeRadius, String.format("%.1f", distSq), wpName);
                toRemove.add(wpName);
            }
        }

        // 5. 删除（统一在 mc.execute 主线程调用，因为 Xaero 反射必须在主线程）
        if (!toRemove.isEmpty()) {
            for (String name : toRemove) {
                ClientCargoCache.removeWaypoint(name);
            }
        }
    }

    /**
     * 从路径点名称里提取订单 ID 候选（用于状态兜底删除的匹配）
     * 支持格式：
     *   - 货运订单 #20110b3aef32...  → 20110b3aef32...
     *   - 订单起点#20110b3aef32...     → 20110b3aef32...
     *   - 起点 货运 #20110b3a          → 20110b3a
     *   - 纯字符串（没 #，但全是 hex 数字） → 直接返回
     */
    @org.jetbrains.annotations.Nullable
    private static String extractOrderIdFromWaypointName(String wpName) {
        if (wpName == null) return null;
        int idx = wpName.lastIndexOf('#');
        if (idx >= 0 && idx + 1 < wpName.length()) {
            String s = wpName.substring(idx + 1).trim();
            // 去掉尾部空格，返回非空片段
            if (!s.isEmpty()) return s;
        }
        // 没有 # 的格式兜底：如果整个字符串长度 >=4 且只含 hex/数字，当成 orderId
        String s2 = wpName.trim();
        if (s2.length() >= 4) {
            boolean hex = true;
            for (int i = 0; i < s2.length(); i++) {
                char c = s2.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    hex = false;
                    break;
                }
            }
            if (hex) return s2;
        }
        return null;
    }
}
