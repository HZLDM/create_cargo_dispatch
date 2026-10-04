package com.hzldm.createcargodispatch.network;

import com.hzldm.createcargodispatch.CreateCargoDispatch;
import com.hzldm.createcargodispatch.company.CompanyService;
import com.hzldm.createcargodispatch.menu.CompanyMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联合运输系统网络包注册与处理（独立于 {@link ModPayloads}，防止其继续膨胀）
 *
 * 原理：
 *  - 6 个 C2S 操作包全部 enqueueWork 切到服务端主线程后委托 {@link CompanyService}
 *  - 1 个 S2C 同步包在客户端主线程整表写入 ClientCompanyCache（见 ClientPayloadHandlers）
 *  - 所有异常 exceptionally 兜底，单个坏包不影响连接
 */
public final class CompanyPayloadHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-CompanyNet");

    private CompanyPayloadHandlers() {
    }

    /** 注册入口：由主类构造时挂到 modEventBus */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CreateCargoDispatch.MODID);
        registrar
                .playToServer(OpenCompanyMenuPayload.TYPE, OpenCompanyMenuPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleOpenMenu)
                .playToServer(CreateCompanyPayload.TYPE, CreateCompanyPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleCreate)
                .playToServer(JoinCompanyPayload.TYPE, JoinCompanyPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleJoin)
                .playToServer(LeaveCompanyPayload.TYPE, LeaveCompanyPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleLeave)
                .playToServer(KickCompanyMemberPayload.TYPE, KickCompanyMemberPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleKick)
                .playToServer(InviteCompanyMemberPayload.TYPE, InviteCompanyMemberPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleInvite)
                .playToServer(UpgradeCompanyLevelPayload.TYPE, UpgradeCompanyLevelPayload.STREAM_CODEC,
                        CompanyPayloadHandlers::handleUpgradeLevel);
        // S2C 处理器引用 ClientCompanyCache，移到 @OnlyIn(Dist.CLIENT) 的 ClientPayloadHandlers
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.hzldm.createcargodispatch.client.ClientPayloadHandlers.registerCompanyClient(registrar);
        }
    }

    /** 打开联合运输页：先推最新状态再开菜单（与订单页同一套 0 RTT 模式） */
    private static void handleOpenMenu(OpenCompanyMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            CompanyService.sendSync(player);
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new CompanyMenu(id, inv),
                    Component.translatable("create_cargo_dispatch.company.title")));
        }).exceptionally(ex -> {
            LOGGER.error("打开联合运输菜单失败", ex);
            return null;
        });
    }

    private static void handleCreate(CreateCompanyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.createCompany(player, payload.name());
            }
        }).exceptionally(ex -> {
            LOGGER.error("创建联合运输失败", ex);
            return null;
        });
    }

    private static void handleJoin(JoinCompanyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.joinCompany(player, payload.companyId());
            }
        }).exceptionally(ex -> {
            LOGGER.error("加入联合运输失败", ex);
            return null;
        });
    }

    private static void handleLeave(LeaveCompanyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.leaveCompany(player);
            }
        }).exceptionally(ex -> {
            LOGGER.error("退出联合运输失败", ex);
            return null;
        });
    }

    private static void handleKick(KickCompanyMemberPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.kickMember(player, payload.targetId());
            }
        }).exceptionally(ex -> {
            LOGGER.error("踢出联合运输成员失败", ex);
            return null;
        });
    }

    private static void handleInvite(InviteCompanyMemberPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.inviteByName(player, payload.playerName());
            }
        }).exceptionally(ex -> {
            LOGGER.error("邀请联合运输成员失败", ex);
            return null;
        });
    }

    private static void handleUpgradeLevel(UpgradeCompanyLevelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CompanyService.upgradeCompanyLevel(player);
            }
        }).exceptionally(ex -> {
            LOGGER.error("提升公司等级失败", ex);
            return null;
        });
    }
}
