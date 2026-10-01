package com.hzldm.createcargodispatch.integration.jade;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jade（玉）HUD 集成入口
 *
 * 为什么这样写：
 *  - Jade 是可选软依赖，不能在主流程直接引用 Jade API，否则 Jade 不存在时 ClassNotFoundException
 *  - init() 只在 ModList.isLoaded("jade") 为真时调用，且里面的类引用（JadeProviders, IWailaPlugin 等）
 *    都发生在 isLoaded 判断之后，类加载器才会去解析这些符号
 *  - 即便反射调用失败也只打印 warn 日志，不影响主游戏
 */
public final class JadeIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Jade");

    private JadeIntegration() {}

    /**
     * 在 CreateCargoDispatch 主类构造末尾调用
     * 仅当 Jade 已安装时才反射触发实际注册
     */
    public static void init() {
        if (!net.neoforged.fml.ModList.get().isLoaded("jade")) {
            LOGGER.debug("[CargoDispatch] Jade 未安装，跳过 HUD 集成");
            return;
        }
        try {
            // 反射加载：避免类加载器在 Jade 缺失时解析 JadeProviders
            Class<?> cls = Class.forName(
                    "com.hzldm.createcargodispatch.integration.jade.JadeProviders",
                    true,
                    JadeIntegration.class.getClassLoader()
            );
            // 注册 Provider：Jade 会在 @WailaPlugin 注解的类自动扫描
            cls.getMethod("register").invoke(null);
            LOGGER.info("[CargoDispatch] Jade HUD 集成已启用");
        } catch (Throwable t) {
            // 失败降级：只打 warn，不影响游戏
            LOGGER.warn("[CargoDispatch] Jade HUD 集成初始化失败（不影响游戏）：{}",
                    t.getMessage());
            CreateCargoDispatch.LOGGER.debug("Jade 集成失败详情", t);
        }
    }
}
