package com.hzldm.createcargodispatch.cargo;

/**
 * 货箱平衡性规则：默认货物数量、容量、尺寸→价格系数的单一真源。
 *
 * <h3>默认数量（已整体下调，减轻重量）</h3>
 * 以标准 3×3×9（{@link CargoDimensions#SMALL}）为基线给出区间，
 * 再按结构体积线性放大；矿石/方块类物品（矿山）因单件更重，数量减半。
 *
 * <h3>物理容量硬顶</h3>
 * 订单数量绝不允许超过货箱真实库存容量（格数×该物品堆叠上限），
 * 从根上消除「订单显示上千件、实际装不下被静默丢弃」的 Bug。
 *
 * <h3>尺寸→价格</h3>
 * 大件搬运更困难，基础货运币按体积线性补贴（{@link #sizePriceFactor}）；
 * 物品量本身的收益仍由 count×perCargo 项体现，二者不重复叠加。
 */
public final class CargoBalance {

    /** 标准尺寸下，每单货物数量下限（普通件） */
    public static final int SMALL_MIN_ITEMS = 32;
    /** 标准尺寸下，每单货物数量上限（普通件） */
    public static final int SMALL_MAX_ITEMS = 96;
    /** 矿石/方块类（单件重）数量系数：1/2 */
    public static final int HEAVY_NUM = 1, HEAVY_DEN = 2;

    /** 货箱库存格数（与 CargoBlockEntity 实际格数严格一致，容量计算的单一真源） */
    public static final int INVENTORY_SLOTS = 27;

    /** 大件基础补贴：体积每比标准多 1 倍，基础币增加该比例 */
    public static final double SIZE_BASE_WEIGHT = 0.5D;

    private CargoBalance() {}

    /** 体积相对标准（27 块）的倍数 */
    private static double volumeFactor(CargoDimensions dims) {
        return dims.blockCount() / (double) CargoDimensions.SMALL.blockCount();
    }

    /** 该类型+尺寸下货物数量下限（按 64 堆叠的理论值，配置页预估用） */
    public static int minItems(StationType type, CargoDimensions dims) {
        double f = volumeFactor(dims);
        int base = (int) Math.round(SMALL_MIN_ITEMS * f);
        return clampAtLeastOne(isHeavy(type) ? base * HEAVY_NUM / HEAVY_DEN : base);
    }

    /** 该类型+尺寸下货物数量上限（按 64 堆叠的理论值，配置页预估用） */
    public static int maxItems(StationType type, CargoDimensions dims) {
        double f = volumeFactor(dims);
        int base = (int) Math.round(SMALL_MAX_ITEMS * f);
        return clampAtLeastOne(isHeavy(type) ? base * HEAVY_NUM / HEAVY_DEN : base);
    }

    /** 该类型+尺寸+具体物品下货物数量下限（受货箱物理容量硬顶约束） */
    public static int minItems(StationType type, CargoDimensions dims, net.minecraft.world.item.Item item) {
        return Math.min(minItems(type, dims), physicalCapacity(item));
    }

    /** 该类型+尺寸+具体物品下货物数量上限（受货箱物理容量硬顶约束） */
    public static int maxItems(StationType type, CargoDimensions dims, net.minecraft.world.item.Item item) {
        return Math.min(maxItems(type, dims), physicalCapacity(item));
    }

    /** 调试货箱/默认填充：取区间偏低的一个确定数量（重量友好） */
    public static int defaultItems(StationType type, CargoDimensions dims) {
        int lo = minItems(type, dims);
        int hi = maxItems(type, dims);
        int n = lo + (hi - lo) / 3;
        return Math.max(1, n);
    }

    /** 调试货箱/默认填充（具体物品）：受物理容量硬顶约束 */
    public static int defaultItems(StationType type, CargoDimensions dims, net.minecraft.world.item.Item item) {
        int lo = minItems(type, dims, item);
        int hi = maxItems(type, dims, item);
        int n = lo + (hi - lo) / 3;
        return Math.max(1, n);
    }

    /** 指定物品在货箱内的物理容量上限 = 格数 × 该物品单件堆叠；null 物品按 64 兜底 */
    public static int physicalCapacity(net.minecraft.world.item.Item item) {
        int stack = item != null ? item.getDefaultMaxStackSize() : 64;
        return INVENTORY_SLOTS * Math.max(1, stack);
    }

    /** 重件整数减半可能得到 0，数量至少为 1 */
    private static int clampAtLeastOne(int n) {
        return Math.max(1, n);
    }

    /**
     * 尺寸→基础币系数：标准=1；越大越高（线性于体积）。
     * <p>cargo 5×5×15（约 13.9 倍体积）→ 1 + 0.5×12.9 ≈ 7.4
     */
    public static double sizePriceFactor(CargoDimensions dims) {
        double extra = volumeFactor(dims) - 1.0D;
        return 1.0D + SIZE_BASE_WEIGHT * Math.max(0.0D, extra);
    }

    /** 矿山源/矿石方块类按重件减半 */
    private static boolean isHeavy(StationType type) {
        return type == StationType.MINE;
    }
}
