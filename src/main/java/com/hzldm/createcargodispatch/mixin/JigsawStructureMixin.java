package com.hzldm.createcargodispatch.mixin;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * JigsawStructure Mixin：mine/farm 结构生成时检查地形平坦度
 *
 * 原理：
 *  - 拦截 JigsawStructure.findGenerationPoint 返回值
 *  - 通过 startPool 的 ResourceLocation 识别 create_cargo_dispatch:mine 和 create_cargo_dispatch:farm
 *  - 采样结构中心 9 个点 WORLD_SURFACE_WG 高度，max-min > 阈值则拒绝生成
 *
 * 注意：
 *  - 生产环境（SRG 映射）下 startPool 字段名是 f_xxxx，不能用字段名字符串反射，
 *    因此改为按字段类型（Holder<StructureTemplatePool>）匹配查找，跨环境兼容
 */
@Mixin(JigsawStructure.class)
public class JigsawStructureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch");
    private static final int FLATNESS_THRESHOLD = 5;

    // 缓存找到的 startPool 字段（按类型匹配），避免每次反射扫描
    private static volatile Field cachedStartPoolField;
    private static final Object FIELD_LOCK = new Object();

    @Inject(method = "findGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void vsstars$checkFlatness(Structure.GenerationContext context,
                                        CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> result = cir.getReturnValue();
        if (result.isEmpty()) return;

        JigsawStructure self = (JigsawStructure) (Object) this;

        // 识别是否为 create_cargo_dispatch 的 mine/farm 结构
        Optional<ResourceLocation> poolId = vsstars$getStartPoolId(self);
        if (poolId.isEmpty()) return;
        String ns = poolId.get().getNamespace();
        String path = poolId.get().getPath();
        if (!"create_cargo_dispatch".equals(ns)) return;
        if (!path.contains("mine") && !path.contains("farm")) return;

        try {
            var chunkGenerator = context.chunkGenerator();
            var heightAccessor = context.heightAccessor();
            var randomState = context.randomState();

            Structure.GenerationStub stub = result.get();
            BlockPos center = stub.position();

            int centerX = center.getX();
            int centerZ = center.getZ();
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;

            // 采样 9 个点（3x3 网格，间隔 8 格）
            for (int dx = -8; dx <= 8; dx += 8) {
                for (int dz = -8; dz <= 8; dz += 8) {
                    int h = chunkGenerator.getBaseHeight(
                            centerX + dx, centerZ + dz,
                            Heightmap.Types.WORLD_SURFACE_WG,
                            heightAccessor, randomState);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }

            int range = max - min;
            if (range > FLATNESS_THRESHOLD) {
                LOGGER.info("[CargoDispatch] 拒绝生成 {} @ ({},{}): 高度差 {} > {}",
                        poolId.get(), centerX, centerZ, range, FLATNESS_THRESHOLD);
                cir.setReturnValue(Optional.empty());
            } else {
                LOGGER.debug("[CargoDispatch] 允许生成 {} @ ({},{}): 高度差 {} <= {}",
                        poolId.get(), centerX, centerZ, range, FLATNESS_THRESHOLD);
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] JigsawStructureMixin 平坦度检查失败: {}", t.toString());
        }
    }

    /**
     * 通过反射按字段类型（Holder<StructureTemplatePool>）查找 startPool 并获取 ResourceLocation
     *
     * 原理：
     *  - 不依赖字段名（开发环境 startPool / 生产环境 f_xxxx），而是通过泛型类型匹配
     *  - JigsawStructure 中只有一个 Holder<StructureTemplatePool> 类型的字段，即 startPool
     *  - 首次查找后缓存 Field 引用，使用 double-checked locking 保证线程安全
     */
    private static Optional<ResourceLocation> vsstars$getStartPoolId(JigsawStructure structure) {
        try {
            Field f = cachedStartPoolField;
            if (f == null) {
                synchronized (FIELD_LOCK) {
                    f = cachedStartPoolField;
                    if (f == null) {
                        f = vsstars$findStartPoolField();
                        if (f == null) {
                            LOGGER.warn("[CargoDispatch] 未找到 JigsawStructure 的 startPool 字段（Holder<StructureTemplatePool>）");
                            return Optional.empty();
                        }
                        f.setAccessible(true);
                        cachedStartPoolField = f;
                    }
                }
            }
            Object holder = f.get(structure);
            if (holder instanceof Holder<?> h) {
                return h.unwrapKey().map(k -> k.location());
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] 获取 startPool 失败: {}", t.toString());
        }
        return Optional.empty();
    }

    /**
     * 在 JigsawStructure 的声明字段中，找到泛型为 Holder<StructureTemplatePool> 的那个
     * 无论字段名是 startPool（Mojang名）还是 f_xxx（SRG名）都能命中
     */
    private static Field vsstars$findStartPoolField() {
        for (Field f : JigsawStructure.class.getDeclaredFields()) {
            // 快速过滤：字段类型必须是 Holder
            if (!Holder.class.isAssignableFrom(f.getType())) continue;
            // 检查泛型参数是否为 StructureTemplatePool
            Type gType = f.getGenericType();
            if (gType instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 1 && args[0] instanceof Class<?> clazz
                        && StructureTemplatePool.class.isAssignableFrom(clazz)) {
                    return f;
                }
                // 部分情况下泛型可能被擦除为 Holder，退化为遍历所有 Holder 字段并按值判断
                if (args.length == 1 && args[0].getTypeName().contains("StructureTemplatePool")) {
                    return f;
                }
            }
        }
        // 泛型擦除兜底：按类型遍历返回第一个 Holder 字段（JigsawStructure 通常只有一个）
        for (Field f : JigsawStructure.class.getDeclaredFields()) {
            if (Holder.class.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        return null;
    }
}
