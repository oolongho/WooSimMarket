package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.npc.PersonalityProfile;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * 多因素购买意愿判别式（纯计算，无状态）。
 *
 * <p>整合性格 5 维度（price/market/budget/weather/time）+ 物品级 price-sensitivity + 全局 global-mult
 * + 购买动量 momentum，输出 [0,1] 概率。budget 为硬门提前返回 0；其余因子乘性合成后钳制到 [0,1]。
 * 不钳制单个因子，允许各因子 &gt;1（低价/日间/热销加成），相乘后统一收敛。</p>
 *
 * <p>公式（effectiveStandardPrice = standardPrice × priceDrift，含漂移）：
 * <pre>
 * 预算硬门：userPrice &gt; budget × effectiveStandardPrice → P=0
 * 价格因子 = (effectiveStandardPrice / userPrice) ^ effectiveSensitivity
 *      effectiveSensitivity = getItemPriceSensitivity(itemId) × personality.priceSensitivity
 * 天气因子 = 1 − weatherSensitivity × (hasStorm ? 1 : 0)
 * 时间因子 = 1 + timeStrength × (timePreference − 0.5) × 2 × (dayNess − 0.5)
 *      dayNess = (cos(2π × (time − 6000) / 24000) + 1) / 2   // 正午=1 半夜=0
 * 市场因子 = 1 + momentumStrength × (momentum − 0.5) × 2 × personality.marketSensitivity
 *      momentum = MarketManager.getPurchaseMomentum(itemId)   // EMA∈[0,1], 默认0.5
 * 合成 P = clamp[0,1]( priceFactor × weatherFactor × timeFactor × marketFactor × globalMult )
 * </pre></p>
 *
 * <p>线程模型：仅主线程调用（NpcManager.handleReached）。无可变字段，纯函数。</p>
 *
 * @author oolongho
 */
public class PurchaseFormula {

    private final MarketManager marketManager;
    private final ConfigLoader configLoader;

    public PurchaseFormula(MarketManager marketManager, ConfigLoader configLoader) {
        this.marketManager = marketManager;
        this.configLoader = configLoader;
    }

    /**
     * 计算购买概率。
     *
     * @param personality NPC 性格
     * @param itemId      物品 ID（Material 枚举名）
     * @param userPrice   玩家设定售价
     * @param world       NPC 所在世界（用于天气/时间，null 则返回 0）
     * @return 购买概率 [0,1]
     */
    public double calculate(PersonalityProfile personality, String itemId, double userPrice, World world) {
        if (userPrice <= 0 || !Double.isFinite(userPrice) || world == null) {
            return 0.0;
        }

        boolean dbg = configLoader.isDebugPurchase();
        // 有效标准价（含漂移）：drift 影响 budget 硬门与 priceFactor 基准，使 NPC 响应市场均衡价
        double standardPrice = marketManager.getEffectiveStandardPrice(itemId);
        double drift = marketManager.getPriceDrift(itemId);

        // 预算硬门：budget 是公平价倍数，基准用 effectiveStandardPrice（含漂移）随市场均衡波动
        double budgetLimit = personality.budget() * standardPrice;
        if (userPrice > budgetLimit) {
            if (dbg) {
                Bukkit.getLogger().info(String.format(
                    "[WooSimMarket][Purchase] %s/%s price=%.2f > budget门=%.2f(%.1f×%.2f, drift=%.2f) → P=0.000 拒绝",
                    personality.name(), itemId, userPrice, budgetLimit,
                    personality.budget(), standardPrice, drift));
            }
            return 0.0;
        }

        // 价格因子（effectiveStandardPrice 含漂移，作为价格基准）
        double effectiveSensitivity = marketManager.getItemPriceSensitivity(itemId) * personality.priceSensitivity();
        double priceFactor = Math.pow(standardPrice / userPrice, effectiveSensitivity);

        // 天气因子
        double weatherFactor = 1.0 - personality.weatherSensitivity() * (world.hasStorm() ? 1.0 : 0.0);

        // 时间因子（中性 timePreference=0.5 时恒=1）
        double timeStrength = configLoader.getMarketTimeStrength();
        double dayNess = (Math.cos(2.0 * Math.PI * (world.getTime() - 6000) / 24000.0) + 1.0) / 2.0;
        double timeFactor = 1.0 + timeStrength * (personality.timePreference() - 0.5) * 2.0 * (dayNess - 0.5);

        // 市场因子（momentum=0.5 时恒=1，向后兼容；trend-follower 强跟风，independent 几乎不受影响）
        double momentumStrength = configLoader.getMarketMomentumStrength();
        double momentum = marketManager.getPurchaseMomentum(itemId);
        double marketFactor = 1.0 + momentumStrength * (momentum - 0.5) * 2.0 * personality.marketSensitivity();

        // 合成并钳制最终概率
        double globalMult = configLoader.getMarketGlobalMultiplier();
        double p = Math.max(0.0, Math.min(1.0, priceFactor * weatherFactor * timeFactor * marketFactor * globalMult));

        if (dbg) {
            Bukkit.getLogger().info(String.format(
                "[WooSimMarket][Purchase] %s/%s price=%.2f effStd=%.2f(drift=%.2f) | budget门=%.2f通过 | priceF=%.3f(sens=%.2f) | weather=%.2f time=%.2f(dayNess=%.2f) marketF=%.3f(mom=%.2f) global=%.2f | → P=%.3f",
                personality.name(), itemId, userPrice, standardPrice, drift, budgetLimit,
                priceFactor, effectiveSensitivity,
                weatherFactor, timeFactor, dayNess, marketFactor, momentum, globalMult, p));
        }
        return p;
    }
}
