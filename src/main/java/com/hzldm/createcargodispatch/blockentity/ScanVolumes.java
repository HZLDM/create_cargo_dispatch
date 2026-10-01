package com.hzldm.createcargodispatch.blockentity;

import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.core.BlockPos;

/**
 * 检测体积几何共享工具。
 *
 * 原理：检测器与生成器共用「扫描中心相对自身上移 range_y_offset」规则，
 * 集中为一处出口，偏移语义变更只改 ModConfig 默认/此方法。
 */
public final class ScanVolumes {

    private ScanVolumes() {}

    /** 扫描/范围检测的垂直中心（默认上方 4 格） */
    public static BlockPos centerAbove(BlockPos selfPos) {
        return selfPos.above(ModConfig.getDetectorRangeYOffset());
    }
}
