package com.hzldm.createcargodispatch.integration.create;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 向 Create 注册本模组机器方块的移动许可。
 *
 * <p>根因：Create/航空学的拖拽装配（SimAssembly）继承 {@link BlockMovementChecks}，
 * 默认对「带未声明移动支持的 BlockEntity」方块判为不可移动，抛 AssemblyException：
 * “无法移动的方块”。本模组所有机器（生成器/检测器/连接器/货箱）都是纯逻辑方块、
 * 无附魔/刷怪笼类不可移动语义，故统一注册为允许移动。
 *
 * <p>SRP：判定逻辑集中此类，主类只调一次 init。
 */
public final class CargoMovementAllowance {

    private CargoMovementAllowance() {}

    /** 注册移动允许检查（Create 为可选依赖，需在其已加载时调用） */
    public static void init() {
        BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) -> {
            if (isOurMachine(state)) {
                return BlockMovementChecks.CheckResult.SUCCESS;
            }
            return BlockMovementChecks.CheckResult.PASS;
        });
    }

    private static boolean isOurMachine(BlockState state) {
        net.minecraft.resources.ResourceLocation key = state.getBlock().builtInRegistryHolder().key().location();
        return CreateCargoDispatch.MODID.equals(key.getNamespace());
    }
}
