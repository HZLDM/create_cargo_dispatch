package com.hzldm.createcargodispatch.cargo;

import com.hzldm.createcargodispatch.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 奖励计算与随机订单构造（包私有，SRP：纯函数，不持有/修改任何池状态）。
 *
 * <h3>奖励公式</h3>
 * {@code coin = round(base*sizeFactor + count*perCargo + distance*distanceCoin)}，受 maxCoin 钳制。
 * 订单构造与「尺寸变更重算」共用 {@link #computeReward}，保证两条路径奖励口径一致。
 */
final class RewardCalculator {

    private RewardCalculator() {}

    /**
     * 计算订单最终奖励（含上限钳制）。
     *
     * @param sourceType 起点类型（决定奖励档）
     * @param dims       货箱尺寸（大件补贴）
     * @param count      货物数量
     * @param distance   起终点直线距离（主要收益因子）
     */
    static int computeReward(StationType sourceType, CargoDimensions dims, int count, double distance) {
        ModConfig.RewardConfig rc = ModConfig.getRewardConfig(sourceType);
        int reward = (int) Math.round(
                rc.baseCoin() * CargoBalance.sizePriceFactor(dims)
                        + count * rc.perCargoCoin()
                        + distance * rc.distanceCoinPerBlock());
        return Math.min(reward, rc.maxCoin());
    }

    /**
     * 构造一个随机订单（选物品、数量、奖励、过期时间），不入池（由调用方入池）。
     * 参数语义同原 OrderManager.generateRandomOrder。
     */
    static OrderData randomOrder(BlockPos sourcePos, ResourceLocation sourceDim,
                                  StationType sourceType, StationType targetType,
                                  BlockPos targetPos, ResourceLocation targetDim,
                                  long gameTime, CargoDimensions dims) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String orderId = UUID.randomUUID().toString().substring(0, 8);

        // 从起点货物池选物品
        List<Item> pool = sourceType.getCargoPool();
        Item cargoItem = pool.get(rng.nextInt(pool.size()));
        String itemId = BuiltInRegistries.ITEM.getKey(cargoItem).toString();

        // 数量：按尺寸/类型，硬顶为该物品真实容量
        int minCount = CargoBalance.minItems(sourceType, dims, cargoItem);
        int maxCount = Math.max(minCount + 1, CargoBalance.maxItems(sourceType, dims, cargoItem) + 1);
        int count = rng.nextInt(minCount, maxCount);

        // 奖励：距离为主要收益因子
        double distance = Math.sqrt(sourcePos.distSqr(targetPos));
        int reward = computeReward(sourceType, dims, count, distance);

        OrderData order = new OrderData(orderId, sourcePos, sourceDim, targetPos, targetDim, TransportType.LAND,
                itemId, count, reward, sourceType, targetType, gameTime);
        order.setDimensions(dims);

        // 未接过期时间：随机 ttl；min/max 都为 0 视为永不过期
        long expireMin = ModConfig.getOrderExpireMinTicks();
        long expireMax = ModConfig.getOrderExpireMaxTicks();
        if (expireMin <= 0L && expireMax <= 0L) {
            order.setExpireAtGameTime(0L);
        } else {
            long ttl = expireMax > expireMin
                    ? rng.nextLong(expireMin, expireMax + 1L) : expireMin;
            order.setExpireAtGameTime(gameTime + ttl);
        }
        return order;
    }
}
