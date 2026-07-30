package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.util.TaskUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态市场管理器 —— 72 桶滑动窗口统计全服 NPC 购买量，按供需调整基准价。
 *
 * <p>核心公式：
 * <ul>
 *   <li>{@code Multiplier = TargetVolume / CurrentTotalPurchases}（钳制 [0.1, 5.0]）</li>
 *   <li>{@code FinalBase = StandardPrice × Multiplier^exponent}</li>
 * </ul></p>
 *
 * <p>滑动窗口：{@code bucketCount}（默认 72）个时间桶，每 {@code bucketMinutes}
 * （默认 5）分钟滚动一次（清最老桶、推进当前桶索引），覆盖 {@code 72 × 5 = 360} 分钟 = 6 小时。</p>
 *
 * <p>线程模型：所有方法在主线程执行（recordPurchase 由 NpcManager.handlePurchase
 * 主线程调用，rollBuckets 由主线程定时任务触发）。</p>
 *
 * <p>物品数据来源：{@code items.yml}（标准价 + 目标销量）。未在 items.yml 中定义的
 * 物品使用 {@link #DEFAULT_BASE_PRICE} 兜底。</p>
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

    /** 循环桶数组：每个桶存储 itemId → 该时间段内的购买量。 */
    private final Map<String, Integer>[] buckets;

    /** 当前活跃桶索引（0 ~ bucketCount-1）。 */
    private int currentBucketIndex;

    /** 桶滚动定时任务。 */
    private BukkitTask rollTask;

    // 市场参数（从 configLoader 读取）
    private final double multiplierMin;
    private final double multiplierMax;
    private final double multiplierExponent;
    private final int bucketCount;
    private final int bucketMinutes;

    /** 物品标准价信息。 */
    public record ItemInfo(String itemId, double standardPrice, int targetVolume) {}

    @SuppressWarnings("unchecked")
    public MarketManager(WooSimMarket plugin, ConfigLoader configLoader) {
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.multiplierMin = configLoader.getMarketMultiplierMin();
        this.multiplierMax = configLoader.getMarketMultiplierMax();
        this.multiplierExponent = configLoader.getMarketMultiplierExponent();
        this.bucketCount = configLoader.getMarketBucketCount();
        this.bucketMinutes = configLoader.getMarketBucketMinutes();

        this.buckets = new Map[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new HashMap<>();
        }
        this.currentBucketIndex = 0;
    }

    /**
     * 启动市场系统：加载 items.yml 并启动桶滚动定时任务。
     */
    public void start() {
        loadItems();
        long periodTicks = bucketMinutes * 60L * 20L;
        // 首次延迟 = periodTicks（让第一个桶有完整的数据采集周期）
        rollTask = TaskUtil.runAtFixed(plugin, this::rollBuckets, periodTicks, periodTicks);
        plugin.getLogger().info(() -> String.format(
                "动态市场已启动: %d 桶 × %d 分钟 = %d 小时窗口",
                bucketCount, bucketMinutes, bucketCount * bucketMinutes / 60));
    }

    /**
     * 停止市场系统：取消滚动任务。
     */
    public void stop() {
        if (rollTask != null) {
            rollTask.cancel();
            rollTask = null;
        }
    }

    /**
     * 记录一次 NPC 购买（在当前时间桶中累加该物品的购买量）。
     *
     * @param itemId 物品 ID（Material 枚举名，如 DIAMOND）
     */
    public void recordPurchase(String itemId) {
        buckets[currentBucketIndex].merge(itemId, 1, Integer::sum);
    }

    /**
     * 获取指定物品的动态基准价（供 NPC 购买判定使用）。
     *
     * <p>公式：{@code FinalBase = StandardPrice × Multiplier^exponent}<br>
     * 未在 items.yml 中定义的物品返回 {@link #DEFAULT_BASE_PRICE}。</p>
     *
     * @param itemId 物品 ID
     * @return 动态基准价
     */
    public double getFinalBase(String itemId) {
        ItemInfo info = itemInfos.get(itemId);
        if (info == null) {
            return DEFAULT_BASE_PRICE;
        }
        double multiplier = computeMultiplier(itemId);
        return info.standardPrice() * Math.pow(multiplier, multiplierExponent);
    }

    /**
     * 计算指定物品的市场倍率。
     *
     * <p>公式：{@code Multiplier = TargetVolume / CurrentTotalPurchases}（钳制 [min, max]）<br>
     * 无购买记录时返回 {@code multiplierMax}（鼓励购买）。</p>
     *
     * @param itemId 物品 ID
     * @return 市场倍率 [multiplierMin, multiplierMax]
     */
    private double computeMultiplier(String itemId) {
        ItemInfo info = itemInfos.get(itemId);
        if (info == null) {
            return 1.0;
        }

        int totalPurchases = 0;
        for (Map<String, Integer> bucket : buckets) {
            totalPurchases += bucket.getOrDefault(itemId, 0);
        }

        if (totalPurchases == 0) {
            return multiplierMax;
        }

        double multiplier = (double) info.targetVolume() / totalPurchases;
        return Math.max(multiplierMin, Math.min(multiplierMax, multiplier));
    }

    /**
     * 滚动时间桶：推进当前桶索引并清空新桶（丢弃最老数据）。
     */
    private void rollBuckets() {
        currentBucketIndex = (currentBucketIndex + 1) % bucketCount;
        buckets[currentBucketIndex].clear();

        if (configLoader.isDebug()) {
            plugin.getLogger().info(() -> String.format(
                    "市场桶滚动: index=%d/%d", currentBucketIndex, bucketCount - 1));
        }
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
            int targetVolume = itemSection.getInt("target-volume", 100);
            itemInfos.put(itemId, new ItemInfo(itemId, standardPrice, targetVolume));
        }

        plugin.getLogger().info(() -> "items.yml 加载完成: " + itemInfos.size() + " 个物品");
    }
}
