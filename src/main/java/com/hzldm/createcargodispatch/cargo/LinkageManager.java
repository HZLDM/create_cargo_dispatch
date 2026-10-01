package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.company.Company;
import com.hzldm.createcargodispatch.company.CompanyStore;
import com.hzldm.createcargodispatch.company.ReputationRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 联络线管理器（SavedData 持久化）
 *
 * 公司化模型：
 *  - 站点连接是「公司资产」：companyStations 按 companyId 存储，同公司成员共享同一份连接
 *  - 对外 API 仍以 playerId 入参（OrderManager/UI 零改动），内部经 playerBinding 解析其所在公司
 *  - 无公司玩家一律得到空连接/拒绝写入，配合 CompanyService 实现「必须加入公司才能货运」
 *  - playerBinding/companyMembers 为内存索引（不持久化）：权威来自 CompanyStore，
 *    归属变更由 CompanyService 显式同步，玩家登录时按 CompanyStore 重建
 *  - 公司解散时 {@link #removeCompany} 级联删除连接/防刷单标记/冷却/缓存
 *
 * 防刷单：companyGeneratedTypes 记录公司已生成过订单的类型，连同类型站点不重复触发，3 天周期清空。
 */
public class LinkageManager extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Linkage");
    private static final String DATA_NAME = "create_cargo_dispatch_linkages";

    /** 断开后重连冷却（tick）= 2 分钟，防止断开→立刻重连刷订单 */
    public static final int DISCONNECT_RECONNECT_COOLDOWN_TICKS = 20 * 60 * 2;

    /** 公司已连接站点：companyId → 站点列表 */
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<ConnectedStation>> companyStations = new ConcurrentHashMap<>();
    /** 公司已生成过订单的类型：companyId → 类型集合 */
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<StationType>> companyGeneratedTypes = new ConcurrentHashMap<>();
    /** 断开→重连冷却：companyId → (站点位置 → 断开 tick)，内存不持久化 */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<BlockPos, Long>> companyCooldown = new ConcurrentHashMap<>();

    /** 玩家→公司绑定（内存索引） */
    private final ConcurrentHashMap<UUID, UUID> playerBinding = new ConcurrentHashMap<>();
    /** 公司→成员反查（内存索引，断站清理需按公司全体成员取消配送单） */
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<UUID>> companyMembers = new ConcurrentHashMap<>();

    // 热路径 O(1) 缓存，键为 companyId（同公司成员共享缓存）
    private final ConcurrentHashMap<UUID, Set<Long>> cachedPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<StationType>> cachedNotifyTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<StationType>> cachedTypes = new ConcurrentHashMap<>();

    public LinkageManager() {
    }

    // =====================================================
    // 加载 / 保存（新格式按公司存储；旧玩家格式数据直接忽略，模组未发布不做迁移）
    // =====================================================

    public static LinkageManager load(CompoundTag tag, HolderLookup.Provider registries) {
        LinkageManager mgr = new LinkageManager();
        ListTag companies = tag.getList("Companies", 10);
        for (int i = 0; i < companies.size(); i++) {
            CompoundTag ce = companies.getCompound(i);
            try {
                UUID companyId = ce.getUUID("CompanyId");
                CopyOnWriteArrayList<ConnectedStation> list = new CopyOnWriteArrayList<>();
                ListTag stations = ce.getList("Stations", 10);
                for (int j = 0; j < stations.size(); j++) {
                    CompoundTag st = stations.getCompound(j);
                    list.add(new ConnectedStation(
                            BlockPos.of(st.getLong("Pos")),
                            StationType.byId(st.getString("Type")),
                            !st.contains("NotifyEnabled") || st.getBoolean("NotifyEnabled")));
                }
                mgr.companyStations.put(companyId, list);
                CopyOnWriteArraySet<StationType> gens = new CopyOnWriteArraySet<>();
                ListTag genTypes = ce.getList("GeneratedTypes", 8);
                for (int j = 0; j < genTypes.size(); j++) {
                    gens.add(StationType.byId(genTypes.getString(j)));
                }
                if (!gens.isEmpty()) mgr.companyGeneratedTypes.put(companyId, gens);
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] 加载公司联络线条目失败，已跳过", t);
            }
        }
        LOGGER.info("[CargoDispatch] 加载联络线存储：{} 个公司", mgr.companyStations.size());
        return mgr;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag companies = new ListTag();
        for (var entry : companyStations.entrySet()) {
            CompoundTag ce = new CompoundTag();
            ce.putUUID("CompanyId", entry.getKey());
            ListTag stations = new ListTag();
            for (ConnectedStation s : entry.getValue()) {
                CompoundTag st = new CompoundTag();
                st.putLong("Pos", s.pos().asLong());
                st.putString("Type", s.type().getId());
                st.putBoolean("NotifyEnabled", s.notifyEnabled());
                stations.add(st);
            }
            ce.put("Stations", stations);
            CopyOnWriteArraySet<StationType> gens = companyGeneratedTypes.get(entry.getKey());
            if (gens != null && !gens.isEmpty()) {
                ListTag genTypes = new ListTag();
                for (StationType t : gens) genTypes.add(net.minecraft.nbt.StringTag.valueOf(t.getId()));
                ce.put("GeneratedTypes", genTypes);
            }
            companies.add(ce);
        }
        tag.put("Companies", companies);
        return tag;
    }

    public static LinkageManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LinkageManager::new, LinkageManager::load),
                DATA_NAME);
    }

    // =====================================================
    // 玩家↔公司绑定索引（由 CompanyService 维护）
    // =====================================================

    /** 玩家加入/创建公司：建立绑定并把公司现有连接推给该玩家 */
    public void bindPlayer(ServerLevel level, UUID playerId, UUID companyId) {
        if (playerId == null || companyId == null) return;
        playerBinding.put(playerId, companyId);
        companyMembers.computeIfAbsent(companyId, k -> new CopyOnWriteArraySet<>()).add(playerId);
        invalidateCompanyCache(companyId);
        pushLinkagesToPlayer(level, playerId);
        LOGGER.info("[CargoDispatch] 绑定玩家 {} → 公司 {}", playerId, companyId);
    }

    /** 玩家离开/被踢：解绑并推送空连接列表（连接是公司资产，留在公司） */
    public void unbindPlayer(ServerLevel level, UUID playerId) {
        if (playerId == null) return;
        UUID companyId = playerBinding.remove(playerId);
        if (companyId == null) return;
        CopyOnWriteArraySet<UUID> members = companyMembers.get(companyId);
        if (members != null) members.remove(playerId);
        invalidateCompanyCache(companyId);
        pushEmptyLinkages(level, playerId);
        LOGGER.info("[CargoDispatch] 解绑玩家 {}（原公司 {})", playerId, companyId);
    }

    /** 公司解散：级联删除连接/防刷单标记/冷却/缓存，并给全体在线成员推空列表 */
    public void removeCompany(ServerLevel level, UUID companyId) {
        if (companyId == null) return;
        companyStations.remove(companyId);
        companyGeneratedTypes.remove(companyId);
        companyCooldown.remove(companyId);
        invalidateCompanyCache(companyId);
        CopyOnWriteArraySet<UUID> members = companyMembers.remove(companyId);
        if (members != null) {
            for (UUID memberId : List.copyOf(members)) {
                playerBinding.remove(memberId);
                pushEmptyLinkages(level, memberId);
            }
        }
        setDirty();
        LOGGER.info("[CargoDispatch] 公司 {} 已级联删除全部联络线数据", companyId);
    }

    /** 登录重建：以 CompanyStore 为权威设置玩家绑定（不推送，登录流程自行推单） */
    public void rebuildBinding(UUID playerId, UUID companyId) {
        if (playerId == null) return;
        UUID old = playerBinding.remove(playerId);
        if (old != null) {
            CopyOnWriteArraySet<UUID> oldMembers = companyMembers.get(old);
            if (oldMembers != null) oldMembers.remove(playerId);
        }
        if (companyId != null) {
            playerBinding.put(playerId, companyId);
            companyMembers.computeIfAbsent(companyId, k -> new CopyOnWriteArraySet<>()).add(playerId);
            invalidateCompanyCache(companyId);
        }
    }

    /** 解析玩家当前公司，无公司返回 null */
    private UUID cid(UUID playerId) {
        return playerId == null ? null : playerBinding.get(playerId);
    }

    // =====================================================
    // 连接 / 断开
    // =====================================================

    public ConnectResult connectStation(ServerLevel level, UUID playerId, BlockPos pos,
                                        StationType type, long gameTickNow) {
        UUID companyId = cid(playerId);
        if (companyId == null) return ConnectResult.companyRequired();
        if (type == StationType.GENERIC) return ConnectResult.ok();
        long remainTicks = getCooldownRemaining(companyId, pos, gameTickNow);
        if (remainTicks > 0) {
            return ConnectResult.cooldown((int) Math.ceil(remainTicks / 20.0));
        }
        CopyOnWriteArrayList<ConnectedStation> list =
                companyStations.computeIfAbsent(companyId, k -> new CopyOnWriteArrayList<>());
        for (ConnectedStation s : list) {
            if (s.pos().equals(pos)) return ConnectResult.alreadyConnected();
        }
        // 声望等级联动的可连接站点槽位上限（声望只增不减，不会出现已连接数反超上限）
        Company company = CompanyStore.get(level).getCompany(companyId);
        int maxSlots = company != null
                ? company.getLinkSlots()
                : ReputationRules.linkSlotsForLevel(1);
        if (list.size() >= maxSlots) {
            return ConnectResult.limitReached(maxSlots, list.size());
        }
        list.add(new ConnectedStation(pos.immutable(), type));
        setDirty();
        invalidateCompanyCache(companyId);
        LOGGER.info("[CargoDispatch] 公司 {} 连接站点 {} ({})", companyId, pos, type.getId());
        return ConnectResult.ok();
    }

    public boolean disconnectStation(UUID playerId, BlockPos pos, ServerLevel level) {
        UUID companyId = cid(playerId);
        if (companyId == null || pos == null) return false;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null) return false;
        StationType removedType = null;
        for (ConnectedStation s : list) {
            if (s.pos().asLong() == pos.asLong()) {
                removedType = s.type();
                break;
            }
        }
        boolean removed = list.removeIf(s -> s.pos().asLong() == pos.asLong());
        if (!removed) return false;
        setDirty();
        invalidateCompanyCache(companyId);
        if (level != null) markCooldown(companyId, pos, level.getGameTime());
        Collection<UUID> members = companyMembers(companyId);
        if (level != null) {
            // 公司维度订单清理：该公司（含离线）成员配送中的相关单取消，该公司该站未接单清除（不影响其他公司）
            try {
                OrderManager.cancelAcceptedOrdersForCompanyAndStation(companyId, pos, removedType, level);
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] 断站取消公司 {} ACCEPTED 订单异常 pos={}", companyId, pos, t);
            }
            try {
                OrderManager.clearPendingOrdersForCompanyDisconnectedStation(companyId, level, pos, removedType);
            } catch (Throwable t) {
                LOGGER.error("[CargoDispatch] 断站清除公司 {} PENDING 订单异常 pos={}", companyId, pos, t);
            }
            for (UUID memberId : members) {
                try {
                    OrderManager.pushFilteredPendingOrdersToPlayer(level, memberId);
                } catch (Throwable t) {
                    LOGGER.error("[CargoDispatch] 断站推送公司成员 {} 过滤订单失败", memberId, t);
                }
            }
        }
        cleanupGeneratedTypeIfNoneLeft(companyId, removedType, list);
        pushLinkagesToCompanyMembers(level, companyId);
        LOGGER.info("[CargoDispatch] 公司 {} 断开站点 {}（{}），已通知 {} 名成员",
                companyId, pos, removedType != null ? removedType.getId() : "?", members.size());
        return true;
    }

    /** 站点被破坏：移除所有连接它的公司的记录，并逐公司清理订单 */
    public void removeStationByPos(BlockPos pos, ServerLevel level) {
        if (pos == null) return;
        long packed = pos.asLong();
        List<UUID> affectedCompanies = new ArrayList<>();
        for (var entry : companyStations.entrySet()) {
            CopyOnWriteArrayList<ConnectedStation> lst = entry.getValue();
            StationType removedType = null;
            for (ConnectedStation s : lst) {
                if (s.pos().asLong() == packed) {
                    removedType = s.type();
                    break;
                }
            }
            if (!lst.removeIf(s -> s.pos().asLong() == packed)) continue;
            UUID companyId = entry.getKey();
            affectedCompanies.add(companyId);
            invalidateCompanyCache(companyId);
            if (level != null) markCooldown(companyId, pos, level.getGameTime());
            cleanupOrphanGeneratedTypes(companyId, lst);
            Collection<UUID> members = companyMembers(companyId);
            if (level != null) {
                try {
                    OrderManager.cancelAcceptedOrdersForCompanyAndStation(companyId, pos, removedType, level);
                } catch (Throwable t) {
                    LOGGER.error("[CargoDispatch] 拆站取消公司 {} ACCEPTED 订单异常", companyId, t);
                }
                try {
                    OrderManager.clearPendingOrdersForCompanyDisconnectedStation(companyId, level, pos, removedType);
                } catch (Throwable t) {
                    LOGGER.error("[CargoDispatch] 拆站清除公司 {} PENDING 订单异常", companyId, t);
                }
                for (UUID memberId : members) {
                    try {
                        OrderManager.pushFilteredPendingOrdersToPlayer(level, memberId);
                    } catch (Throwable t) {
                        LOGGER.error("[CargoDispatch] 拆站推送成员 {} 过滤订单失败", memberId, t);
                    }
                }
            }
        }
        if (!affectedCompanies.isEmpty()) {
            setDirty();
            for (UUID companyId : affectedCompanies) pushLinkagesToCompanyMembers(level, companyId);
            LOGGER.info("[CargoDispatch] 站点 {} 被破坏，影响 {} 个公司的连接，已逐公司清理订单", pos, affectedCompanies.size());
        }
    }

    // =====================================================
    // 查询（玩家入参→解析公司，无公司一律空视图）
    // =====================================================

    public List<ConnectedStation> getConnectedStations(UUID playerId) {
        UUID companyId = cid(playerId);
        if (companyId == null) return List.of();
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        return list != null ? List.copyOf(list) : List.of();
    }

    public List<ConnectedStation> getConnectedStationsByType(UUID playerId, StationType type) {
        UUID companyId = cid(playerId);
        if (companyId == null || type == null) return List.of();
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null) return List.of();
        List<ConnectedStation> result = new ArrayList<>();
        for (ConnectedStation s : list) if (s.type() == type) result.add(s);
        return result;
    }

    public List<StationType> getConnectedTypes(UUID playerId) {
        UUID companyId = cid(playerId);
        if (companyId == null) return List.of();
        List<StationType> cached = cachedTypes.get(companyId);
        if (cached != null) return cached;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null || list.isEmpty()) {
            List<StationType> prev = cachedTypes.putIfAbsent(companyId, List.of());
            return prev != null ? prev : List.of();
        }
        ArrayList<StationType> tmp = new ArrayList<>(4);
        for (ConnectedStation s : list) {
            if (s != null && s.type() != null && !tmp.contains(s.type())) tmp.add(s.type());
        }
        List<StationType> immutable = tmp.isEmpty() ? List.of() : List.copyOf(tmp);
        List<StationType> prev = cachedTypes.putIfAbsent(companyId, immutable);
        return prev != null ? prev : immutable;
    }

    public Set<Long> getConnectedPositionsPacked(UUID playerId) {
        UUID companyId = cid(playerId);
        if (companyId == null) return Set.of();
        Set<Long> cached = cachedPositions.get(companyId);
        if (cached != null) return cached;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null || list.isEmpty()) {
            Set<Long> prev = cachedPositions.putIfAbsent(companyId, Set.of());
            return prev != null ? prev : Set.of();
        }
        java.util.HashSet<Long> tmp = new java.util.HashSet<>((int) (list.size() / 0.75F) + 1);
        for (ConnectedStation s : list) if (s != null && s.pos() != null) tmp.add(s.pos().asLong());
        Set<Long> immutable = tmp.isEmpty() ? Set.of() : Set.copyOf(tmp);
        Set<Long> prev = cachedPositions.putIfAbsent(companyId, immutable);
        return prev != null ? prev : immutable;
    }

    public Set<StationType> getNotifyEnabledTypes(UUID playerId) {
        UUID companyId = cid(playerId);
        if (companyId == null) return Set.of();
        Set<StationType> cached = cachedNotifyTypes.get(companyId);
        if (cached != null) return cached;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null || list.isEmpty()) {
            Set<StationType> prev = cachedNotifyTypes.putIfAbsent(companyId, Set.of());
            return prev != null ? prev : Set.of();
        }
        java.util.EnumSet<StationType> tmp = java.util.EnumSet.noneOf(StationType.class);
        for (ConnectedStation s : list) if (s.notifyEnabled() && s.type() != null) tmp.add(s.type());
        Set<StationType> immutable = tmp.isEmpty() ? Set.of() : Set.copyOf(tmp);
        Set<StationType> prev = cachedNotifyTypes.putIfAbsent(companyId, immutable);
        return prev != null ? prev : immutable;
    }

    public boolean hasAnyEnabledNotifyForStationType(UUID playerId, StationType type) {
        return playerId != null && type != null && getNotifyEnabledTypes(playerId).contains(type);
    }

    public boolean isStationConnected(UUID playerId, BlockPos pos) {
        UUID companyId = cid(playerId);
        if (companyId == null || pos == null) return false;
        return getConnectedPositionsPacked(playerId).contains(pos.asLong());
    }

    /** 带同结构距离兜底（XZ≤48/Y≤16）的连接校验 */
    public boolean isStationConnected(UUID playerId, BlockPos pos, StationType expectType) {
        UUID companyId = cid(playerId);
        if (companyId == null || pos == null) return false;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null || list.isEmpty()) return false;
        for (ConnectedStation s : list) {
            if (s == null || s.pos() == null) continue;
            if (s.pos().asLong() == pos.asLong()) return true;
            if (expectType == null || s.type() == null || s.type() != expectType) continue;
            int dx = Math.abs(s.pos().getX() - pos.getX());
            int dy = Math.abs(s.pos().getY() - pos.getY());
            int dz = Math.abs(s.pos().getZ() - pos.getZ());
            if (dx <= 48 && dz <= 48 && dy <= 16) return true;
        }
        return false;
    }

    // =====================================================
    // 通知开关（公司级：任一成员切换，全体成员同步）
    // =====================================================

    public boolean setStationNotifyEnabled(ServerLevel level, UUID playerId, BlockPos pos, boolean enabled) {
        UUID companyId = cid(playerId);
        if (companyId == null || pos == null) return false;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null) return false;
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            ConnectedStation s = list.get(i);
            if (!s.pos().equals(pos)) continue;
            if (s.notifyEnabled() == enabled) break;
            list.set(i, new ConnectedStation(s.pos(), s.type(), enabled));
            changed = true;
            break;
        }
        if (changed) {
            setDirty();
            invalidateCompanyCache(companyId);
            pushLinkagesToCompanyMembers(level, companyId);
        }
        return changed;
    }

    public int setAllStationsNotifyEnabled(ServerLevel level, UUID playerId, boolean enabled) {
        UUID companyId = cid(playerId);
        if (companyId == null) return 0;
        CopyOnWriteArrayList<ConnectedStation> list = companyStations.get(companyId);
        if (list == null) return 0;
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            ConnectedStation s = list.get(i);
            if (s.notifyEnabled() == enabled) continue;
            list.set(i, new ConnectedStation(s.pos(), s.type(), enabled));
            changed++;
        }
        if (changed > 0) {
            setDirty();
            invalidateCompanyCache(companyId);
            pushLinkagesToCompanyMembers(level, companyId);
        }
        return changed;
    }

    // =====================================================
    // 防刷单类型标记（公司维度）
    // =====================================================

    public boolean hasGeneratedType(UUID playerId, StationType type) {
        UUID companyId = cid(playerId);
        if (type == null || type == StationType.GENERIC) return true;
        if (companyId == null) return true; // 无公司视为已生成，杜绝绕过
        CopyOnWriteArraySet<StationType> set = companyGeneratedTypes.get(companyId);
        return set != null && set.contains(type);
    }

    public void markTypeGenerated(UUID playerId, StationType type) {
        UUID companyId = cid(playerId);
        if (companyId == null || type == null || type == StationType.GENERIC) return;
        companyGeneratedTypes.computeIfAbsent(companyId, k -> new CopyOnWriteArraySet<>()).add(type);
        setDirty();
    }

    public void clearAllGeneratedTypes() {
        companyGeneratedTypes.clear();
        setDirty();
        LOGGER.info("[CargoDispatch] 已清空所有公司的订单生成类型记录（周期刷新）");
    }

    private void cleanupGeneratedTypeIfNoneLeft(UUID companyId, StationType type, List<ConnectedStation> list) {
        if (type == null || type == StationType.GENERIC) return;
        for (ConnectedStation s : list) if (s.type() == type) return;
        CopyOnWriteArraySet<StationType> set = companyGeneratedTypes.get(companyId);
        if (set != null && set.remove(type)) {
            setDirty();
            if (set.isEmpty()) companyGeneratedTypes.remove(companyId);
        }
    }

    private void cleanupOrphanGeneratedTypes(UUID companyId, List<ConnectedStation> list) {
        CopyOnWriteArraySet<StationType> gens = companyGeneratedTypes.get(companyId);
        if (gens == null || gens.isEmpty()) return;
        java.util.EnumSet<StationType> connected = java.util.EnumSet.noneOf(StationType.class);
        for (ConnectedStation s : list) if (s != null && s.type() != null) connected.add(s.type());
        boolean changed = false;
        for (StationType t : java.util.EnumSet.copyOf(gens)) {
            if (!connected.contains(t) && gens.remove(t)) changed = true;
        }
        if (gens.isEmpty()) companyGeneratedTypes.remove(companyId);
        if (changed) setDirty();
    }

    // =====================================================
    // 冷却（公司维度）
    // =====================================================

    private void markCooldown(UUID companyId, BlockPos pos, long tick) {
        companyCooldown.computeIfAbsent(companyId, k -> new ConcurrentHashMap<>(4)).put(pos.immutable(), tick);
    }

    private long getCooldownRemaining(UUID companyId, BlockPos pos, long now) {
        ConcurrentHashMap<BlockPos, Long> map = companyCooldown.get(companyId);
        if (map == null) return 0;
        Long last = map.get(pos);
        if (last == null) return 0;
        long remaining = DISCONNECT_RECONNECT_COOLDOWN_TICKS - (now - last);
        if (remaining <= 0) {
            map.remove(pos);
            if (map.isEmpty()) companyCooldown.remove(companyId);
            return 0;
        }
        return remaining;
    }

    // =====================================================
    // 缓存 / 推送
    // =====================================================

    private void invalidateCompanyCache(UUID companyId) {
        if (companyId == null) return;
        cachedPositions.remove(companyId);
        cachedNotifyTypes.remove(companyId);
        cachedTypes.remove(companyId);
    }

    private Collection<UUID> companyMembers(UUID companyId) {
        CopyOnWriteArraySet<UUID> set = companyMembers.get(companyId);
        return set != null ? List.copyOf(set) : List.of();
    }

    private void pushLinkagesToCompanyMembers(ServerLevel level, UUID companyId) {
        if (level == null) return;
        for (UUID memberId : companyMembers(companyId)) pushLinkagesToPlayer(level, memberId);
    }

    /** 按操作者解析其公司，把最新连接列表推给全体在线成员（新站连接后全员同步） */
    public void pushCompanyLinkagesToMembers(ServerLevel level, UUID operatorId) {
        UUID companyId = cid(operatorId);
        if (companyId != null) pushLinkagesToCompanyMembers(level, companyId);
    }

    /** 向玩家推送其当前公司连接（无公司则推空） */
    public void pushLinkagesToPlayer(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return; // 离线不推，上线打开菜单时服务端会重发
        List<com.hzldm.createcargodispatch.network.SyncLinkagesPayload.LinkageEntry> entries =
                getConnectedStations(playerId).stream()
                        .map(s -> new com.hzldm.createcargodispatch.network.SyncLinkagesPayload.LinkageEntry(
                                s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.type().getId(), s.notifyEnabled()))
                        .toList();
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new com.hzldm.createcargodispatch.network.SyncLinkagesPayload(entries));
        } catch (Exception e) {
            LOGGER.error("[CargoDispatch] 推送 SyncLinkagesPayload 失败 player={}", playerId, e);
        }
    }

    private void pushEmptyLinkages(ServerLevel level, UUID playerId) {
        if (level == null || playerId == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new com.hzldm.createcargodispatch.network.SyncLinkagesPayload(List.of()));
        }
    }

    public void clear() {
        companyStations.clear();
        companyGeneratedTypes.clear();
        companyCooldown.clear();
        playerBinding.clear();
        companyMembers.clear();
        cachedPositions.clear();
        cachedNotifyTypes.clear();
        cachedTypes.clear();
        setDirty();
    }

    /**
     * 连接结果。
     * @param limitReached 因公司声望等级对应的槽位上限被拒绝
     * @param limitSlots   上限槽位数（提示用）
     * @param currentCount 当前已连接站点数（提示用）
     */
    public static record ConnectResult(boolean connected, boolean duplicated, int cooldownSecs,
                                       boolean noCompany, boolean limitReached,
                                       int limitSlots, int currentCount) {
        public static ConnectResult ok() { return new ConnectResult(true, false, 0, false, false, 0, 0); }
        public static ConnectResult alreadyConnected() { return new ConnectResult(false, true, 0, false, false, 0, 0); }
        public static ConnectResult cooldown(int seconds) { return new ConnectResult(false, false, seconds, false, false, 0, 0); }
        public static ConnectResult companyRequired() { return new ConnectResult(false, false, 0, true, false, 0, 0); }
        public static ConnectResult limitReached(int limitSlots, int currentCount) {
            return new ConnectResult(false, false, 0, false, true, limitSlots, currentCount);
        }
    }

    /** 已连接的站点记录 */
    public static record ConnectedStation(BlockPos pos, StationType type, boolean notifyEnabled) {
        public ConnectedStation(BlockPos pos, StationType type) {
            this(pos, type, true);
        }
    }
}
