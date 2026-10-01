package com.hzldm.createcargodispatch.company;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联合运输公司持久化存储（SavedData）
 *
 * 原理：
 *  - 仿 LinkageManager/OrderStore：数据存主世界 DataStorage，重启自动恢复
 *  - companies：公司实体表；playerCompany：玩家→公司反查索引（O(1) 判定归属）
 *  - 索引不持久化，加载时由各公司成员列表重建，避免双写不一致
 *  - 跨维度访问统一重定向到主世界存储，保证全服唯一实例
 *  - 本类只管理公司状态本身；订单切换/网络通知由 CompanyService 编排
 */
public class CompanyStore extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger("CargoDispatch-Company");
    private static final String DATA_NAME = "create_cargo_dispatch_companies";

    /** 公司名长度限制（按字符数） */
    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 16;

    private final ConcurrentHashMap<UUID, Company> companies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> playerCompany = new ConcurrentHashMap<>();

    public CompanyStore() {
    }

    // =====================================================
    // 加载 / 保存
    // =====================================================

    public static CompanyStore load(CompoundTag tag, HolderLookup.Provider registries) {
        CompanyStore store = new CompanyStore();
        ListTag list = tag.getList("Companies", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag ct = list.getCompound(i);
            try {
                UUID id = ct.getUUID("Id");
                String name = ct.getString("Name");
                UUID creator = ct.getUUID("Creator");
                long createdAt = ct.getLong("CreatedAt");
                Company company = new Company(id, name, creator, createdAt);
                // 兜底：即使成员列表数据异常缺少创建者，也保证其归属索引正确
                store.playerCompany.put(creator, id);
                ListTag members = ct.getList("Members", 8);
                for (int j = 0; j < members.size(); j++) {
                    UUID member = UUID.fromString(members.getString(j));
                    // 构造器已加入 creator，其余成员补齐
                    company.addMember(member);
                    store.playerCompany.put(member, id);
                }
                // 经济三要素：旧存档无字段时默认 0/0/1（loadEconomy 内部夹取）
                company.loadEconomy(ct.getLong("Balance"), ct.getInt("Reputation"),
                        ct.contains("CompanyLevel") ? ct.getInt("CompanyLevel") : 1);
                store.companies.put(id, company);
            } catch (Throwable t) {
                // 单条损坏不影响整体加载
                LOGGER.error("[CargoDispatch] 加载联合运输公司数据条目失败，已跳过", t);
            }
        }
        LOGGER.info("[CargoDispatch] 加载联合运输存储：{} 个公司，{} 名成员",
                store.companies.size(), store.playerCompany.size());
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Company c : companies.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putUUID("Id", c.getId());
            ct.putString("Name", c.getName());
            ct.putUUID("Creator", c.getCreator());
            ct.putLong("CreatedAt", c.getCreatedAt());
            ListTag members = new ListTag();
            for (UUID m : c.members()) {
                members.add(StringTag.valueOf(m.toString()));
            }
            ct.put("Members", members);
            ct.putLong("Balance", c.getBalance());
            ct.putInt("Reputation", c.getReputation());
            ct.putInt("CompanyLevel", c.getCompanyLevel());
            list.add(ct);
        }
        tag.put("Companies", list);
        return tag;
    }

    /** 获取存储：任意维度调用都重定向主世界，保证唯一实例 */
    public static CompanyStore get(ServerLevel level) {
        ServerLevel target = level;
        if (level.getServer() != null) {
            ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) target = overworld;
        }
        return target.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CompanyStore::new, CompanyStore::load),
                DATA_NAME);
    }

    // =====================================================
    // 查询
    // =====================================================

    public Company getCompany(UUID companyId) {
        return companyId == null ? null : companies.get(companyId);
    }

    public UUID getCompanyIdOfPlayer(UUID playerId) {
        return playerId == null ? null : playerCompany.get(playerId);
    }

    public Company getCompanyOfPlayer(UUID playerId) {
        UUID id = getCompanyIdOfPlayer(playerId);
        return id == null ? null : companies.get(id);
    }

    public boolean isMember(UUID companyId, UUID playerId) {
        if (companyId == null || playerId == null) return false;
        return companyId.equals(playerCompany.get(playerId));
    }

    /** 全部公司（创建时间有序快照），用于可加入列表 */
    public List<Company> listCompanies() {
        List<Company> list = new ArrayList<>(companies.values());
        list.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return list;
    }

    /**
     * 校验并规范化公司名
     * @return null=合法，返回值=失败语言键
     */
    public String validateName(String raw) {
        if (raw == null) return "create_cargo_dispatch.company.invalid_name";
        String name = raw.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            return "create_cargo_dispatch.company.invalid_name";
        }
        // 禁用章节符号（颜色代码注入）与换行
        if (name.indexOf('§') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            return "create_cargo_dispatch.company.invalid_name";
        }
        for (Company c : companies.values()) {
            if (c.getName().equalsIgnoreCase(name)) {
                return "create_cargo_dispatch.company.duplicate_name";
            }
        }
        return null;
    }

    // =====================================================
    // 变更（调用方须先完成业务校验；方法保证索引一致 + 落盘）
    // =====================================================

    /** 创建公司，创建者自动入司；创建者已在其他公司时返回 null（原子防并发重复创建） */
    public Company create(UUID creator, String name, long createdAt) {
        Company company = new Company(UUID.randomUUID(), name.trim(), creator, createdAt);
        // 先原子占位归属：防止并发请求把同一个玩家写进两家公司
        if (playerCompany.putIfAbsent(creator, company.getId()) != null) {
            return null;
        }
        companies.put(company.getId(), company);
        setDirty();
        return company;
    }

    /** 成员加入；公司不存在/已在公司时返回 false（归属原子写入，并发加入两公司必有一败） */
    public boolean join(UUID companyId, UUID playerId) {
        Company company = companies.get(companyId);
        if (company == null) return false;
        // 先原子占归属槽：putIfAbsent 返回非 null 表示已有公司
        if (playerCompany.putIfAbsent(playerId, companyId) != null) return false;
        if (!company.addMember(playerId)) {
            playerCompany.remove(playerId, companyId); // 成员列表已存在时回滚归属
            return false;
        }
        setDirty();
        return true;
    }

    /** 成员退出（非创建者），返回其原公司 */
    public Company leave(UUID playerId) {
        UUID companyId = playerCompany.get(playerId);
        if (companyId == null) return null;
        Company company = companies.get(companyId);
        if (company == null || company.isCreator(playerId)) return null;
        company.removeMember(playerId);
        playerCompany.remove(playerId);
        setDirty();
        return company;
    }

    /** 创建者踢人，目标不能是创建者；返回被踢者原公司（目标可离线） */
    public Company kick(UUID operator, UUID targetId) {
        UUID companyId = playerCompany.get(operator);
        if (companyId == null) return null;
        Company company = companies.get(companyId);
        if (company == null || !company.isCreator(operator)) return null;
        if (company.isCreator(targetId) || !companyId.equals(playerCompany.get(targetId))) return null;
        company.removeMember(targetId);
        playerCompany.remove(targetId);
        setDirty();
        return company;
    }

    /** 解散：清掉公司与全部成员索引，返回解散前成员快照 */
    public List<UUID> disband(UUID creatorId) {
        UUID companyId = playerCompany.get(creatorId);
        if (companyId == null) return List.of();
        Company company = companies.get(companyId);
        if (company == null || !company.isCreator(creatorId)) return List.of();
        List<UUID> members = company.members();
        for (UUID m : members) {
            playerCompany.remove(m);
        }
        companies.remove(companyId);
        setDirty();
        return members;
    }

    // =====================================================
    // 经济事务（货运币 / 声望 / 公司等级）
    // =====================================================

    /** 订单结算结果：供服务层判断是否触发声望升级广播 */
    public record SettlementResult(long newBalance, int oldRepLevel, int newRepLevel) {
    }

    /**
     * 订单完成结算：货运币入公司账户 + 累加声望（同一原子入口）。
     * @return 结算结果；公司不存在返回 null
     */
    public SettlementResult settleOrder(UUID companyId, long coin, int reputationGain) {
        Company company = companies.get(companyId);
        if (company == null) return null;
        int oldLevel = company.getReputationLevel();
        if (coin != 0L) company.addBalance(coin);
        if (reputationGain > 0) company.addReputation(reputationGain);
        int newLevel = company.getReputationLevel();
        setDirty();
        return new SettlementResult(company.getBalance(), oldLevel, newLevel);
    }

    /** 公司等级晋升：原子扣费 + 升级（费用由 CompanyLevelRules 计算后传入） */
    public Company.LevelUpResult tryUpgradeCompany(UUID companyId, long cost) {
        Company company = companies.get(companyId);
        if (company == null) return Company.LevelUpResult.INSUFFICIENT_FUNDS;
        Company.LevelUpResult result = company.trySpendAndLevelUp(cost);
        if (result == Company.LevelUpResult.OK) setDirty();
        return result;
    }
}
