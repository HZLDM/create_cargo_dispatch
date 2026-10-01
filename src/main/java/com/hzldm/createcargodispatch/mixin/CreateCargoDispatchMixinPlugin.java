package com.hzldm.createcargodispatch.mixin;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 插件：仅当前置模组存在时才加载对应 Mixin
 */
public class CreateCargoDispatchMixinPlugin implements IMixinConfigPlugin {

    private static final String LOG_PREFIX = "[CCD-MixinPlugin]";

    @Override
    public void onLoad(String mixinPackage) {
        // LOGGER 在这个阶段不可用，直接 STDOUT 打硬日志到 latest.log（NeoForge 会捕获 stdout）
        System.out.println(LOG_PREFIX + " onLoad: package=" + mixinPackage);
        System.out.println(LOG_PREFIX + " ModList null? " + (ModList.get() == null)
                + (ModList.get() != null ? " sable.isLoaded? " + ModList.get().isLoaded("sable") : ""));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        System.out.println(LOG_PREFIX + " shouldApplyMixin? class=" + mixinClassName
                + " target=" + targetClassName);

        // SubLevelRemovalMixin 仅在 Sable 存在时加载
        if (mixinClassName.contains("SubLevelRemovalMixin")) {
            final boolean ok = ModList.get() != null && ModList.get().isLoaded("sable");
            System.out.println(LOG_PREFIX + "   SubLevelRemovalMixin -> " + ok);
            return ok;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> replacements) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
