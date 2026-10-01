package com.hzldm.createcargodispatch.cargo;

import net.minecraft.nbt.CompoundTag;

/**
 * 货箱三维尺寸（方块数）。
 *
 * <ul>
 *   <li>{@code width} ：水平截面宽（X，主方块左右）</li>
 *   <li>{@code height}：垂直截面高（Y，主方块向上）</li>
 *   <li>{@code length}：长轴长度（Z，主方块两侧延伸）</li>
 * </ul>
 * 三者必须为正奇数：主方块始终位于截面「底部中心」、长轴「正中心」，
 * 结构据此对称生成。尺寸只影响<b>配置后新生成</b>的货箱。
 *
 * <p>预设：{@link #SMALL 3×3×9}、{@link #LARGE 5×5×15}。
 */
public record CargoDimensions(int width, int height, int length) {

    /** 预设：标准 3×3×9（27 块） */
    public static final CargoDimensions SMALL = new CargoDimensions(3, 3, 9);
    /** 预设：大型 5×5×15（375 块） */
    public static final CargoDimensions LARGE = new CargoDimensions(5, 5, 15);
    /** 未记录尺寸时的兼容默认（标准） */
    public static final CargoDimensions DEFAULT = SMALL;

    // 自定义允许范围（奇数步进）
    public static final int MIN_WIDTH = 3,  MAX_WIDTH = 7;
    public static final int MIN_HEIGHT = 3, MAX_HEIGHT = 7;
    public static final int MIN_LENGTH = 3, MAX_LENGTH = 21;

    public CargoDimensions {
        width = clampOdd(width, MIN_WIDTH, MAX_WIDTH);
        height = clampOdd(height, MIN_HEIGHT, MAX_HEIGHT);
        length = clampOdd(length, MIN_LENGTH, MAX_LENGTH);
    }

    /** 结构方块总数（体积） */
    public int blockCount() {
        return width * height * length;
    }

    /** 是否为标准预设 3×3×9 */
    public boolean isSmall() {
        return equals(SMALL);
    }

    /** 是否为大型预设 5×5×15 */
    public boolean isLarge() {
        return equals(LARGE);
    }

    /** 截面半宽（左右格数） */
    public int halfWidth() {
        return width / 2;
    }

    /** 长轴半长（两侧格数） */
    public int halfLength() {
        return length / 2;
    }

    /** 各轴 ±2 微调（宽/高方向），自动夹到合法奇数范围 */
    public CargoDimensions adjustWidth(int delta) {
        return new CargoDimensions(width + delta, height, length);
    }

    public CargoDimensions adjustHeight(int delta) {
        return new CargoDimensions(width, height + delta, length);
    }

    public CargoDimensions adjustLength(int delta) {
        return new CargoDimensions(width, height, length + delta);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("W", width);
        tag.putInt("H", height);
        tag.putInt("L", length);
        return tag;
    }

    public static CargoDimensions load(CompoundTag tag) {
        if (tag == null || !tag.contains("W")) return DEFAULT;
        return new CargoDimensions(tag.getInt("W"), tag.getInt("H"), tag.getInt("L"));
    }

    @Override
    public String toString() {
        return width + "x" + height + "x" + length;
    }

    /** 夹到 [min,max] 内的奇数（偶数向最近奇数收敛）。 */
    static int clampOdd(int v, int min, int max) {
        if ((v & 1) == 0) v += (v <= min) ? 1 : -1;
        if (v < min) v = min;
        if (v > max) v = max;
        if ((v & 1) == 0) v -= 1;
        return Math.max(min, v);
    }
}
