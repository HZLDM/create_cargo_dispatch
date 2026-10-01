package com.hzldm.createcargodispatch.mixin;

import com.hzldm.createcargodispatch.cargo.CargoManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * SubLevel 移除拦截 Mixin
 *
 * 原理：
 *  - 拦截 Sable SubLevel.markRemoved() 方法
 *  - 检查 this 的 UUID 是否为已注册的货箱 SubLevel
 *  - 如果是货箱 SubLevel，阻止移除（防止被取消物理化）
 *  - 使用 @Mixin(targets = "字符串") 避免编译时依赖 Sable
 *
 * 注意：
 *  - targets 指定全限定类名，运行时由 Mixin 注入
 *  - markRemoved() 是无参方法，方法名为 "markRemoved"
 *  - 通过反射调用 getUniqueId() 获取 SubLevel UUID
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.SubLevel")
public class SubLevelRemovalMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Sable");
    private static Method getUniqueIdMethod;
    private static boolean methodResolved = false;

    /**
     * 拦截 markRemoved()：如果是货箱 SubLevel，阻止移除
     * 原理：货箱物理化后不可被取消，确保货物始终为物理化状态
     */
    @Inject(method = "markRemoved", at = @At("HEAD"), cancellable = true)
    private void vsstars$preventCargoRemoval(CallbackInfo ci) {
        if (!CargoManager.hasRegisteredSubLevels()) return;
        try {
            UUID uuid = vsstars$getSubLevelUUID((Object) this);
            if (uuid != null && CargoManager.isCargoSubLevel(uuid)) {
                // 白名单：收货/破坏/拆卸/放弃订单等模组有意删除，直接放行
                if (CargoManager.isSubLevelRemovalAllowed(uuid)) {
                    return;
                }
                LOGGER.info("[CargoDispatch] 阻止货箱 SubLevel {} 被移除（物理化保护）", uuid);
                ci.cancel();
            }
        } catch (Throwable t) {
            LOGGER.warn("[CargoDispatch] SubLevel 移除拦截失败: {}", t.getMessage());
        }
    }

    /**
     * 通过反射获取 SubLevel 的 UUID
     * 原理：Sable SubLevel.getUniqueId() 返回 UUID，首次反射后缓存 Method
     */
    private static UUID vsstars$getSubLevelUUID(Object subLevel) {
        try {
            if (!methodResolved) {
                synchronized (SubLevelRemovalMixin.class) {
                    if (!methodResolved) {
                        Method m = subLevel.getClass().getMethod("getUniqueId");
                        m.setAccessible(true);
                        getUniqueIdMethod = m;
                        methodResolved = true;
                    }
                }
            }
            Object result = getUniqueIdMethod.invoke(subLevel);
            return result instanceof UUID ? (UUID) result : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
