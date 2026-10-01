package com.hzldm.createcargodispatch.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置
 *
 * 原理：
 *  - 使用 NeoForge ModConfigSpec 定义配置项
 *  - 分为服务端配置（SERVER）和客户端配置（CLIENT）
 *  - 使用 Builder.push() / pop() 对配置项分组，ConfigurationScreen 自动生成分组 UI
 *  - 每个配置项通过 .translation("key") 指定翻译键
 *  - tooltip 翻译键为：translationKey + ".tooltip"
 *
 * 分组翻译键格式（ConfigurationScreen 自动解析）：
 *  - Section 标题：create_cargo_dispatch.configuration.<section_path>
 *  - 配置项名称：通过 .translation() 指定
 *  - 配置项说明（鼠标悬停）：<translation_key>.tooltip
 *
 * 奖励计算公式（货运币，订单完成后入公司账户）：
 *  coin = round(base_coin + cargoCount * per_cargo_coin + distance * distance_coin)
 *  若 coin > max_coin 则取 max_coin；distance 为起终点三维直线距离（格），
 *  距离系数远大于单件系数，长途运输收益显著高于短途。
 */
public class ModConfig {

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    // ============================================================
    // 服务端配置
    // ============================================================

    // ---- 检测器配置 ----
    static {
        SERVER_BUILDER.push("detector");  // 分组：detector
    }

    public static final ModConfigSpec.IntValue DETECTOR_RANGE_XZ =
            SERVER_BUILDER.comment("货箱检测器水平检测范围", "默认: 16，范围 1~64")
                    .translation("create_cargo_dispatch.config.detector.range_xz")
                    .defineInRange("range_xz", 16, 1, 64);

    public static final ModConfigSpec.IntValue DETECTOR_RANGE_Y =
            SERVER_BUILDER.comment("货箱检测器垂直检测范围", "默认: 4，范围 1~32")
                    .translation("create_cargo_dispatch.config.detector.range_y")
                    .defineInRange("range_y", 4, 1, 32);

    public static final ModConfigSpec.IntValue DETECTOR_RANGE_Y_OFFSET =
            SERVER_BUILDER.comment("货箱检测器检测范围垂直偏移（正数上移、负数下移）",
                    "默认: 4（检测器所在层成为范围底面），范围 -32~32")
                    .translation("create_cargo_dispatch.config.detector.range_y_offset")
                    .defineInRange("range_y_offset", 4, -32, 32);

    static {
        SERVER_BUILDER.pop();  // 结束 detector 分组
    }

    // ---- 订单系统配置 ----
    static {
        SERVER_BUILDER.push("orders");
    }

    public static final ModConfigSpec.IntValue MAX_ORDERS_PER_STATION =
            SERVER_BUILDER.comment("每个起点站点最大同时存在的订单数量（站点接单上限）",
                    "默认: 12，范围 1~100")
                    .translation("create_cargo_dispatch.config.orders.max_per_station")
                    .defineInRange("max_per_station", 12, 1, 100);

    public static final ModConfigSpec.LongValue ORDER_REFRESH_MIN_INTERVAL =
            SERVER_BUILDER.comment("随机刷新订单最小间隔（tick，20tick=1秒）",
                    "默认: 6000（5分钟），范围 200~72000")
                    .translation("create_cargo_dispatch.config.orders.refresh_min_ticks")
                    .defineInRange("refresh_min_ticks", 6000L, 200L, 72000L);

    public static final ModConfigSpec.LongValue ORDER_REFRESH_MAX_INTERVAL =
            SERVER_BUILDER.comment("随机刷新订单最大间隔（tick）",
                    "默认: 12000（10分钟），范围 600~144000",
                    "实际刷新间隔 = random(min, max)")
                    .translation("create_cargo_dispatch.config.orders.refresh_max_ticks")
                    .defineInRange("refresh_max_ticks", 12000L, 600L, 144000L);

    // ============================================================
    // 需求3：订单未接限时（可配置 + 随机区间）
    // ============================================================
    public static final ModConfigSpec.LongValue ORDER_EXPIRE_MIN_TICKS =
            SERVER_BUILDER.comment("订单「未接」最短存活时长（tick，20tick=1秒）",
                    "默认: 18000（15分钟），范围 200~72000（2小时）",
                    "实际每条 PENDING 订单的过期时间 = createdGameTime + random(min,max)")
                    .translation("create_cargo_dispatch.config.orders.expire_min_ticks")
                    .defineInRange("expire_min_ticks", 18000L, 200L, 72000L);

    public static final ModConfigSpec.LongValue ORDER_EXPIRE_MAX_TICKS =
            SERVER_BUILDER.comment("订单「未接」最长存活时长（tick）",
                    "默认: 24000（20分钟），范围 600~144000（2小时）",
                    "注意：max 必须 ≥ min，否则会被自动取 max=min")
                    .translation("create_cargo_dispatch.config.orders.expire_max_ticks")
                    .defineInRange("expire_max_ticks", 24000L, 600L, 144000L);

    public static final ModConfigSpec.IntValue ORDER_EXPIRE_SCAN_INTERVAL_TICKS =
            SERVER_BUILDER.comment("订单过期扫描频率（多少服务器 tick 检查一次）",
                    "默认: 100（5秒），范围 20~6000。值越小越精准，但越占用 CPU；推荐 100~400 之间")
                    .translation("create_cargo_dispatch.config.orders.expire_scan_interval_ticks")
                    .defineInRange("expire_scan_interval_ticks", 100, 20, 6000);

    static {
        SERVER_BUILDER.pop();
    }

    // ---- 奖励配置（货运币，按货运站类型分组；距离是主要收益因子） ----
    static {
        SERVER_BUILDER.push("reward");
    }

    // -- 伐木场奖励 --
    static {
        SERVER_BUILDER.push("lumber_yard");
    }

    public static final ModConfigSpec.IntValue LUMBER_YARD_BASE_COIN =
            SERVER_BUILDER.comment("伐木场订单基础货运币奖励", "默认: 20")
                    .translation("create_cargo_dispatch.config.reward.lumber_yard.base_coin")
                    .defineInRange("base_coin", 20, 0, 1_000_000);

    public static final ModConfigSpec.DoubleValue LUMBER_YARD_PER_CARGO_COIN =
            SERVER_BUILDER.comment("伐木场每件货物额外货运币", "默认: 0.10")
                    .translation("create_cargo_dispatch.config.reward.lumber_yard.per_cargo_coin")
                    .defineInRange("per_cargo_coin", 0.10, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue LUMBER_YARD_DISTANCE_COIN =
            SERVER_BUILDER.comment("伐木场每格距离的货运币奖励（距离为主要收益因子）",
                    "公式：距离奖励 = round(距离 * 本值)，默认: 0.50")
                    .translation("create_cargo_dispatch.config.reward.lumber_yard.distance_coin")
                    .defineInRange("distance_coin", 0.50, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue LUMBER_YARD_MAX_COIN =
            SERVER_BUILDER.comment("伐木场单次订单货运币上限", "默认: 8000")
                    .translation("create_cargo_dispatch.config.reward.lumber_yard.max_coin")
                    .defineInRange("max_coin", 8000, 1, 1_000_000);

    static {
        SERVER_BUILDER.pop();  // 结束 lumber_yard
    }

    // -- 矿山奖励 --
    static {
        SERVER_BUILDER.push("mine");
    }

    public static final ModConfigSpec.IntValue MINE_BASE_COIN =
            SERVER_BUILDER.comment("矿山订单基础货运币奖励", "默认: 30")
                    .translation("create_cargo_dispatch.config.reward.mine.base_coin")
                    .defineInRange("base_coin", 30, 0, 1_000_000);

    public static final ModConfigSpec.DoubleValue MINE_PER_CARGO_COIN =
            SERVER_BUILDER.comment("矿山每件货物额外货运币", "默认: 0.15")
                    .translation("create_cargo_dispatch.config.reward.mine.per_cargo_coin")
                    .defineInRange("per_cargo_coin", 0.15, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue MINE_DISTANCE_COIN =
            SERVER_BUILDER.comment("矿山每格距离的货运币奖励", "默认: 0.60")
                    .translation("create_cargo_dispatch.config.reward.mine.distance_coin")
                    .defineInRange("distance_coin", 0.60, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue MINE_MAX_COIN =
            SERVER_BUILDER.comment("矿山单次订单货运币上限", "默认: 12000")
                    .translation("create_cargo_dispatch.config.reward.mine.max_coin")
                    .defineInRange("max_coin", 12000, 1, 1_000_000);

    static {
        SERVER_BUILDER.pop();  // 结束 mine
    }

    // -- 农场奖励 --
    static {
        SERVER_BUILDER.push("farm");
    }

    public static final ModConfigSpec.IntValue FARM_BASE_COIN =
            SERVER_BUILDER.comment("农场订单基础货运币奖励", "默认: 20")
                    .translation("create_cargo_dispatch.config.reward.farm.base_coin")
                    .defineInRange("base_coin", 20, 0, 1_000_000);

    public static final ModConfigSpec.DoubleValue FARM_PER_CARGO_COIN =
            SERVER_BUILDER.comment("农场每件货物额外货运币", "默认: 0.10")
                    .translation("create_cargo_dispatch.config.reward.farm.per_cargo_coin")
                    .defineInRange("per_cargo_coin", 0.10, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue FARM_DISTANCE_COIN =
            SERVER_BUILDER.comment("农场每格距离的货运币奖励", "默认: 0.50")
                    .translation("create_cargo_dispatch.config.reward.farm.distance_coin")
                    .defineInRange("distance_coin", 0.50, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue FARM_MAX_COIN =
            SERVER_BUILDER.comment("农场单次订单货运币上限", "默认: 10000")
                    .translation("create_cargo_dispatch.config.reward.farm.max_coin")
                    .defineInRange("max_coin", 10000, 1, 1_000_000);

    static {
        SERVER_BUILDER.pop();  // 结束 farm
    }

    // -- 牧场奖励 --
    static {
        SERVER_BUILDER.push("pasture");
    }

    public static final ModConfigSpec.IntValue PASTURE_BASE_COIN =
            SERVER_BUILDER.comment("牧场订单基础货运币奖励", "默认: 25")
                    .translation("create_cargo_dispatch.config.reward.pasture.base_coin")
                    .defineInRange("base_coin", 25, 0, 1_000_000);

    public static final ModConfigSpec.DoubleValue PASTURE_PER_CARGO_COIN =
            SERVER_BUILDER.comment("牧场每件货物额外货运币", "默认: 0.12")
                    .translation("create_cargo_dispatch.config.reward.pasture.per_cargo_coin")
                    .defineInRange("per_cargo_coin", 0.12, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue PASTURE_DISTANCE_COIN =
            SERVER_BUILDER.comment("牧场每格距离的货运币奖励", "默认: 0.55")
                    .translation("create_cargo_dispatch.config.reward.pasture.distance_coin")
                    .defineInRange("distance_coin", 0.55, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue PASTURE_MAX_COIN =
            SERVER_BUILDER.comment("牧场单次订单货运币上限", "默认: 10000")
                    .translation("create_cargo_dispatch.config.reward.pasture.max_coin")
                    .defineInRange("max_coin", 10000, 1, 1_000_000);

    static {
        SERVER_BUILDER.pop();  // 结束 pasture
    }

    // -- 冶金厂奖励 --
    static {
        SERVER_BUILDER.push("metallurgy");
    }

    public static final ModConfigSpec.IntValue METALLURGY_BASE_COIN =
            SERVER_BUILDER.comment("冶金厂订单基础货运币奖励", "默认: 40")
                    .translation("create_cargo_dispatch.config.reward.metallurgy.base_coin")
                    .defineInRange("base_coin", 40, 0, 1_000_000);

    public static final ModConfigSpec.DoubleValue METALLURGY_PER_CARGO_COIN =
            SERVER_BUILDER.comment("冶金厂每件货物额外货运币", "默认: 0.20")
                    .translation("create_cargo_dispatch.config.reward.metallurgy.per_cargo_coin")
                    .defineInRange("per_cargo_coin", 0.20, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue METALLURGY_DISTANCE_COIN =
            SERVER_BUILDER.comment("冶金厂每格距离的货运币奖励", "默认: 0.70")
                    .translation("create_cargo_dispatch.config.reward.metallurgy.distance_coin")
                    .defineInRange("distance_coin", 0.70, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue METALLURGY_MAX_COIN =
            SERVER_BUILDER.comment("冶金厂单次订单货运币上限", "默认: 16000")
                    .translation("create_cargo_dispatch.config.reward.metallurgy.max_coin")
                    .defineInRange("max_coin", 16000, 1, 1_000_000);

    static {
        SERVER_BUILDER.pop();  // 结束 metallurgy
    }

    static {
        SERVER_BUILDER.pop();  // 结束 reward 总分组
    }

    // ---- 公司成长配置（声望 / 公司等级 / 可链接站点数） ----
    static {
        SERVER_BUILDER.push("progression");
    }

    public static final ModConfigSpec.DoubleValue REP_PER_COIN =
            SERVER_BUILDER.comment("每 1 货运币奖励折算的声望值",
                    "完成订单获得声望 = max(每单保底, round(货运币 * 本值))，默认: 0.5")
                    .translation("create_cargo_dispatch.config.progression.rep_per_coin")
                    .defineInRange("rep_per_coin", 0.5, 0.0, 100.0);

    public static final ModConfigSpec.IntValue REP_MIN_PER_ORDER =
            SERVER_BUILDER.comment("每单保底声望（短途单也有成长）", "默认: 10")
                    .translation("create_cargo_dispatch.config.progression.rep_min_per_order")
                    .defineInRange("rep_min_per_order", 10, 0, 100000);

    public static final ModConfigSpec.IntValue LINK_SLOTS_BASE =
            SERVER_BUILDER.comment("声望 1 级时可链接的站点数量", "默认: 2")
                    .translation("create_cargo_dispatch.config.progression.link_slots_base")
                    .defineInRange("link_slots_base", 2, 1, 64);

    public static final ModConfigSpec.IntValue LINK_SLOTS_PER_LEVEL =
            SERVER_BUILDER.comment("声望每升 1 级增加的可链接站点数量", "默认: 1")
                    .translation("create_cargo_dispatch.config.progression.link_slots_per_level")
                    .defineInRange("link_slots_per_level", 1, 0, 64);

    public static final ModConfigSpec.LongValue COMPANY_UPGRADE_COST_BASE =
            SERVER_BUILDER.comment("公司等级晋升费用基数",
                    "公式：升到下一级费用 = 基数 * 当前等级^2，默认: 500",
                    "即 1→2 需 500、2→3 需 2000、3→4 需 4500、4→5 需 8000 货运币")
                    .translation("create_cargo_dispatch.config.progression.upgrade_cost_base")
                    .defineInRange("upgrade_cost_base", 500L, 1L, 1_000_000_000L);

    static {
        SERVER_BUILDER.pop();  // 结束 progression
    }

    // ---- 通知总闸配置（服务端全局，管理员控制；玩家/按站级开关见 LinkageManager 的 notifyEnabled） ----
    static {
        SERVER_BUILDER.push("notification");
    }

    public static final ModConfigSpec.BooleanValue NEW_ORDER_SOUND_ENABLED =
            SERVER_BUILDER.comment("服务端全局总开关：向玩家推送新订单提示音",
                    "默认: 开启；关闭后所有玩家都收不到新订单提示音（玩家级开关见已连接站点的铃铛按钮）")
                    .translation("create_cargo_dispatch.config.notification.new_order_sound")
                    .define("new_order_sound", true);

    public static final ModConfigSpec.BooleanValue NEW_ORDER_CHAT_ENABLED =
            SERVER_BUILDER.comment("服务端全局总开关：发送新订单聊天消息",
                    "默认: 开启；关闭后所有玩家都收不到新订单聊天提示（玩家级开关见已连接站点的铃铛按钮）")
                    .translation("create_cargo_dispatch.config.notification.new_order_chat")
                    .define("new_order_chat", true);

    static {
        SERVER_BUILDER.pop();  // 结束 notification 分组
    }

    public static final ModConfigSpec SERVER_CONFIG = SERVER_BUILDER.build();

    // ============================================================
    // 客户端配置
    // ============================================================

    // ---- 通知配置（客户端本地设置，仅影响本机播放/渲染行为） ----
    static {
        CLIENT_BUILDER.push("notification");
    }

    public static final ModConfigSpec.BooleanValue NEW_ORDER_AUTO_WAYPOINT =
            CLIENT_BUILDER.comment("点击聊天栏位置文本时自动添加 Xaero 路径点",
                    "默认: 开启；关闭则点击位置文本不执行任何操作")
                    .translation("create_cargo_dispatch.config.notification.auto_add_waypoint")
                    .define("auto_add_waypoint", true);

    public static final ModConfigSpec.BooleanValue WAYPOINT_AUTO_REMOVE =
            CLIENT_BUILDER.comment("玩家到达订单路径点附近后自动删除路径点",
                    "默认: 开启；半径由下方选项设置")
                    .translation("create_cargo_dispatch.config.notification.waypoint_auto_remove")
                    .define("waypoint_auto_remove", true);

    public static final ModConfigSpec.IntValue WAYPOINT_REMOVE_RADIUS =
            CLIENT_BUILDER.comment("路径点自动删除半径（方块）",
                    "玩家进入路径点此范围内视为到达，默认: 16")
                    .translation("create_cargo_dispatch.config.notification.waypoint_remove_radius")
                    .defineInRange("waypoint_remove_radius", 16, 1, 256);

    public static final ModConfigSpec.DoubleValue NOTIFICATION_SOUND_VOLUME =
            CLIENT_BUILDER.comment("新订单等通知提示音音量", "范围: 0.0（静音）~ 1.0（最大），默认: 0.8")
                    .translation("create_cargo_dispatch.config.notification.sound_volume")
                    .defineInRange("sound_volume", 0.8, 0.0, 1.0);

    static {
        CLIENT_BUILDER.pop();  // 结束 notification 分组
    }

    public static final ModConfigSpec CLIENT_CONFIG = CLIENT_BUILDER.build();

    // ============================================================
    // 便捷读取方法（含异常保护，避免配置加载前读取导致崩溃）
    // ============================================================

    public static int getDetectorRangeXZ() {
        try { return DETECTOR_RANGE_XZ.get(); } catch (Exception e) { return 16; }
    }

    public static int getDetectorRangeY() {
        try { return DETECTOR_RANGE_Y.get(); } catch (Exception e) { return 4; }
    }

    public static int getDetectorRangeYOffset() {
        try { return DETECTOR_RANGE_Y_OFFSET.get(); } catch (Exception e) { return 4; }
    }

    public static int getMaxOrdersPerStation() {
        try { return MAX_ORDERS_PER_STATION.get(); } catch (Exception e) { return 12; }
    }

    public static long getOrderRefreshMinTicks() {
        try { return ORDER_REFRESH_MIN_INTERVAL.get(); } catch (Exception e) { return 6000L; }
    }

    public static long getOrderRefreshMaxTicks() {
        try { return ORDER_REFRESH_MAX_INTERVAL.get(); } catch (Exception e) { return 12000L; }
    }

    // ---------- 需求3：订单未接限时（min/max + scanInterval） ----------
    /** 随机过期时长 min tick；内部自动保证不会 > max（异常容错 18000 tick ≈ 15 分钟） */
    public static long getOrderExpireMinTicks() {
        try {
            long min = ORDER_EXPIRE_MIN_TICKS.get();
            long max = ORDER_EXPIRE_MAX_TICKS.get();
            return Math.min(min, max);
        } catch (Exception e) { return 18000L; }
    }
    /** 随机过期时长 max tick；内部自动保证不会 < min（异常容错 24000 tick ≈ 20 分钟） */
    public static long getOrderExpireMaxTicks() {
        try {
            long min = ORDER_EXPIRE_MIN_TICKS.get();
            long max = ORDER_EXPIRE_MAX_TICKS.get();
            return Math.max(min, max);
        } catch (Exception e) { return 24000L; }
    }
    /** 过期扫描 tick 间隔（默认每 100 tick ≈ 5 秒扫一次 PENDING_ORDERS） */
    public static int getOrderExpireScanIntervalTicks() {
        try { return ORDER_EXPIRE_SCAN_INTERVAL_TICKS.get(); } catch (Exception e) { return 100; }
    }

    public static boolean isNewOrderSoundEnabled() {
        try { return NEW_ORDER_SOUND_ENABLED.get(); } catch (Exception e) { return true; }
    }

    public static boolean isNewOrderChatEnabled() {
        try { return NEW_ORDER_CHAT_ENABLED.get(); } catch (Exception e) { return true; }
    }

    public static boolean isAutoAddWaypointEnabled() {
        try { return NEW_ORDER_AUTO_WAYPOINT.get(); } catch (Exception e) { return true; }
    }

    public static boolean isWaypointAutoRemoveEnabled() {
        try { return WAYPOINT_AUTO_REMOVE.get(); } catch (Exception e) { return true; }
    }

    public static int getWaypointRemoveRadius() {
        try { return WAYPOINT_REMOVE_RADIUS.get(); } catch (Exception e) { return 16; }
    }

    public static float getNotificationSoundVolume() {
        try { return NOTIFICATION_SOUND_VOLUME.get().floatValue(); } catch (Exception e) { return 0.8f; }
    }

    // ---------- 公司成长配置 ----------

    public static double getRepPerCoin() {
        try { return REP_PER_COIN.get(); } catch (Exception e) { return 0.5; }
    }

    public static int getRepMinPerOrder() {
        try { return REP_MIN_PER_ORDER.get(); } catch (Exception e) { return 10; }
    }

    public static int getLinkSlotsBase() {
        try { return LINK_SLOTS_BASE.get(); } catch (Exception e) { return 2; }
    }

    public static int getLinkSlotsPerLevel() {
        try { return LINK_SLOTS_PER_LEVEL.get(); } catch (Exception e) { return 1; }
    }

    public static long getCompanyUpgradeCostBase() {
        try { return COMPANY_UPGRADE_COST_BASE.get(); } catch (Exception e) { return 500L; }
    }

    /**
     * 根据货运站类型获取货运币奖励配置
     * 原理：每种 StationType 对应独立配置项，GENERIC 降级为伐木场默认值
     */
    public static RewardConfig getRewardConfig(com.hzldm.createcargodispatch.cargo.StationType type) {
        try {
            return switch (type) {
                // GENERIC 无独立奖励组，降级使用伐木场配置
                case LUMBER_YARD, GENERIC -> new RewardConfig(
                        LUMBER_YARD_BASE_COIN.get(),
                        LUMBER_YARD_PER_CARGO_COIN.get(),
                        LUMBER_YARD_DISTANCE_COIN.get(),
                        LUMBER_YARD_MAX_COIN.get());
                case MINE -> new RewardConfig(
                        MINE_BASE_COIN.get(),
                        MINE_PER_CARGO_COIN.get(),
                        MINE_DISTANCE_COIN.get(),
                        MINE_MAX_COIN.get());
                case FARM -> new RewardConfig(
                        FARM_BASE_COIN.get(),
                        FARM_PER_CARGO_COIN.get(),
                        FARM_DISTANCE_COIN.get(),
                        FARM_MAX_COIN.get());
                case PASTURE -> new RewardConfig(
                        PASTURE_BASE_COIN.get(),
                        PASTURE_PER_CARGO_COIN.get(),
                        PASTURE_DISTANCE_COIN.get(),
                        PASTURE_MAX_COIN.get());
                case METALLURGY -> new RewardConfig(
                        METALLURGY_BASE_COIN.get(),
                        METALLURGY_PER_CARGO_COIN.get(),
                        METALLURGY_DISTANCE_COIN.get(),
                        METALLURGY_MAX_COIN.get());
            };
        } catch (Exception e) {
            return new RewardConfig(20, 0.10, 0.50, 8000);
        }
    }

    /**
     * 货运币奖励配置数据类
     *
     * @param baseCoin 基础货运币
     * @param perCargoCoin 每件货物额外货运币
     * @param distanceCoinPerBlock 每格距离货运币（主要收益因子）
     * @param maxCoin 单次订单货运币上限
     */
    public record RewardConfig(int baseCoin, double perCargoCoin,
                                double distanceCoinPerBlock, int maxCoin) {
    }
}
