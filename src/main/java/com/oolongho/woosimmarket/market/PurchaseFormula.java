package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.npc.PersonalityProfile;
import org.bukkit.World;

/**
 * 多因素购买意愿判别式（纯计算，无状态）。
 *
 * <p>整合性格 5 维度（price/market/budget/weather/time）+ 全局热度 + 物品级敏感度，
 * 输出 [0,1] 概率。budget 为硬门提前返回 0；其余因子乘性合成后钳制到 [0,1]。
 * 不钳制单个因子，允许各因子 &gt;1（低价/日间加成），相乘后统一收敛。</p>
 *
 * <p>公式：
 * <pre>
 * ① budget 硬门：userPrice &gt; budget × standardPrice → P=0
 * ② priceFactor = (basePrice / userPrice) ^ effectiveSensitivity
 *      effectiveSensitivity = getItemPriceSensitivity(itemId) × personality.priceSensitivity
 *      effectiveExponent    = getItemMarketSensitivity(itemId) × personality.marketSensitivity
 *      basePrice            = standardPrice × multiplier ^ effectiveExponent
 * ③ weatherFactor = 1 − weatherSensitivity × (hasStorm ? 1 : 0)
 * ④ timeFactor    = 1 + timeStrength × (timePreference − 0.5) × 2 × (dayNess − 0.5)
 *      dayNess = (cos(2π × (time − 6000) / 24000) + 1) / 2   // 正午=1 半夜=0
 * ⑤ P = clamp[0,1]( priceFactor × weatherFactor × timeFactor × globalMult )
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

        double standardPrice = marketManager.getStandardPrice(itemId);

        // ① budget 硬门：budget 是公平价倍数，基准用 standardPrice 不随供需波动
        if (userPrice > personality.budget() * standardPrice) {
            return 0.0;
        }

        // ② priceFactor（含 price + market 两维度）
        double multiplier = marketManager.getMultiplier(itemId);
        double effectiveExponent = marketManager.getItemMarketSensitivity(itemId) * personality.marketSensitivity();
        double basePrice = standardPrice * Math.pow(multiplier, effectiveExponent);
        double effectiveSensitivity = marketManager.getItemPriceSensitivity(itemId) * personality.priceSensitivity();
        double priceFactor = Math.pow(basePrice / userPrice, effectiveSensitivity);

        // ③ weatherFactor
        double weatherFactor = 1.0 - personality.weatherSensitivity() * (world.hasStorm() ? 1.0 : 0.0);

        // ④ timeFactor（中性 timePreference=0.5 时恒=1）
        double timeStrength = configLoader.getMarketTimeStrength();
        double dayNess = (Math.cos(2.0 * Math.PI * (world.getTime() - 6000) / 24000.0) + 1.0) / 2.0;
        double timeFactor = 1.0 + timeStrength * (personality.timePreference() - 0.5) * 2.0 * (dayNess - 0.5);

        // ⑤ 合成并钳制最终概率
        double p = priceFactor * weatherFactor * timeFactor * configLoader.getMarketGlobalMultiplier();
        return Math.max(0.0, Math.min(1.0, p));
    }
}
