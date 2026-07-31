package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品价目表与购买动量管理器。
 *
 * <p>职责一：加载 items.yml 提供物品标准价与价格敏感度查询。
 * 未在 items.yml 中定义的物品使用 {@link #DEFAULT_BASE_PRICE} 兜底。</p>
 *
 * <p>职责二：维护每物品的购买动量 EMA（{@link #purchaseEma}），跟踪近期 NPC
 * 购买结果作为"社交证明/羊群动量"信号，供 {@link PurchaseFormula} 的 marketFactor 使用。
 * EMA ∈ [0,1]，初始 0.5（中性），购买 outcome=1.0，不买 outcome=0.0，α=0.3。
 * 不持久化，重启归零；事件驱动更新（无定时衰减任务）。</p>
 *
 * <p>线程模型：所有方法在主线程执行。</p>
 *
 * @author oolongho
 */
public class MarketManager {

    /** 未在 items.yml 中定义的物品的兜底基准价。 */
    private static final double DEFAULT_BASE_PRICE = 10.0;

    private final WooSimMarket plugin;
    private final ConfigLoader configLoader;

    /** 物品标准价表（itemId → ItemInfo）。 */
    private final Map<String, ItemInfo> itemInfos = new HashMap<>();

    /** 购买动量 EMA 表（itemId → 动量值 ∈[0,1]，缺省 0.5 中性）。不持久化，重启归零。 */
    private final Map<String, Double> purchaseEma = new HashMap<>();

    /** EMA 平滑系数（新结果贡献 30%，历史 70%）。 */
    private static final double EMA_ALPHA = 0.3;

    /** 物品标准价信息。priceSensitivity 为 -1 时用全局默认。 */
    public record ItemInfo(String itemId, double standardPrice, double priceSensitivity) {}

    public MarketManager(WooSimMarket plugin, ConfigLoader configLoader) {
        this.plugin = plugin;
        this.configLoader = configLoader;
    }

    /**
     * 启动市场系统：加载 items.yml。
     */
    public void start() {
        loadItems();
    }

    /**
     * 停止市场系统（桶滚动机制已移除，无任务需取消）。
     */
    public void stop() {
        // 桶滚动机制已移除，无任务需取消
    }

    /**
     * 获取物品的价格敏感度（P 公式指数）。
     *
     * <p>优先返回 items.yml 的 per-item price-sensitivity，缺省（-1）返回全局 sensitivity。</p>
     *
     * @param itemId 物品 ID
     * @return 价格敏感度
     */
    public double getItemPriceSensitivity(String itemId) {
        ItemInfo info = itemInfos.get(itemId);
        if (info != null && info.priceSensitivity() >= 0) {
            return info.priceSensitivity();
        }
        return configLoader.getMarketSensitivity();
    }

    /**
     * 获取物品标准价（items.yml 配置的公平价）。
     *
     * <p>未在 items.yml 中定义的物品返回 {@link #DEFAULT_BASE_PRICE}。
     * 供 PurchaseFormula 的 budget 硬门与 priceFactor 基准使用。</p>
     *
     * @param itemId 物品 ID
     * @return 标准价
     */
    public double getStandardPrice(String itemId) {
        ItemInfo info = itemInfos.get(itemId);
        return info != null ? info.standardPrice() : DEFAULT_BASE_PRICE;
    }

    /**
     * 获取物品的购买动量（近期 NPC 购买结果 EMA）。
     *
     * <p>动量 ∈ [0,1]：1.0=近期 NPC 都买了（热销），0.0=都没买（冷门），
     * 0.5=中性（初始/无记录）。供 {@link PurchaseFormula} 的 marketFactor 使用。</p>
     *
     * @param itemId 物品 ID
     * @return 动量值 ∈ [0,1]，缺省 0.5
     */
    public double getPurchaseMomentum(String itemId) {
        return purchaseEma.getOrDefault(itemId, 0.5);
    }

    /**
     * 记录 NPC 购买判定结果，更新该物品的动量 EMA。
     *
     * <p>EMA 更新：{@code ema = ema × (1 − α) + outcome × α}（α=0.3）。
     * 购买 outcome=1.0，不买 outcome=0.0。主线程调用，无需同步。</p>
     *
     * @param itemId 物品 ID
     * @param bought true=NPC 购买，false=未购买（判定耗尽）
     */
    public void recordPurchaseOutcome(String itemId, boolean bought) {
        double outcome = bought ? 1.0 : 0.0;
        double current = purchaseEma.getOrDefault(itemId, 0.5);
        purchaseEma.put(itemId, current * (1.0 - EMA_ALPHA) + outcome * EMA_ALPHA);
    }

    /**
     * 从 items.yml 加载物品标准价表。
     */
    private void loadItems() {
        File itemsFile = new File(plugin.getDataFolder(), "items.yml");
        if (!itemsFile.exists()) {
            plugin.saveResource("items.yml", false);
        }
        FileConfiguration itemsConfig = YamlConfiguration.loadConfiguration(itemsFile);

        ConfigurationSection itemsSection = itemsConfig.getConfigurationSection("items");
        if (itemsSection == null) {
            plugin.getLogger().warning("items.yml 中未找到 items 节");
            return;
        }

        for (String itemId : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemId);
            if (itemSection == null) {
                continue;
            }
            double standardPrice = itemSection.getDouble("standard-price", DEFAULT_BASE_PRICE);
            // per-item 敏感度覆盖（-1 表示用全局默认）
            double priceSensitivity = itemSection.getDouble("price-sensitivity", -1.0);
            itemInfos.put(itemId, new ItemInfo(itemId, standardPrice, priceSensitivity));
        }

        plugin.getLogger().info(() -> "items.yml 加载完成: " + itemInfos.size() + " 个物品");
    }
}
