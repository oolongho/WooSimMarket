package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.database.DatabaseManager;
import com.oolongho.woosimmarket.database.PurchaseLogDao;
import com.oolongho.woosimmarket.util.TaskUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>职责三：将每次购买判定结果异步写入 {@code purchase_log} 表（供统计面板查询），
 * 并按 {@code stats.retention-days}（默认 7 天）每日异步清理过期记录。</p>
 *
 * <p>职责四：维护每物品的漂移乘数 priceDrift，让 effectiveStandardPrice 根据近期成交价中位数
 * 与购买率缓慢漂移（模拟市场均衡价）。drift 不持久化，重启由 cleanupTask 初始延迟 0 异步重算
 * （跨重启连续）。计算由 {@link DriftCalculator} 纯函数承担，每日异步执行 1 次（复用 cleanupTask
 * 24h 时段），主线程仅读 priceDrift（ConcurrentHashMap 保证线程安全）。</p>
 *
 * <p>线程模型：EMA 更新与配置查询在主线程执行；purchase_log 写入与清理通过
 * {@link TaskUtil} 投递到异步线程，避免阻塞主线程。</p>
 *
 * @author oolongho
 */
public class MarketManager {

    /** 未在 items.yml 中定义的物品的兜底基准价。 */
    private static final double DEFAULT_BASE_PRICE = 10.0;

    private final WooSimMarket plugin;
    private final ConfigLoader configLoader;
    private final PurchaseLogDao purchaseLogDao;

    /** 物品标准价表（itemId → ItemInfo）。
     *  使用 synchronizedMap(LinkedHashMap) 保留 items.yml 配置顺序（供 PriceTableGui 按配置顺序展示），
     *  同时保证异步 recomputeDrift 遍历 + 主线程 reload 写的线程安全。 */
    private final Map<String, ItemInfo> itemInfos = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 购买动量 EMA 表（itemId → 动量值 ∈[0,1]，缺省 0.5 中性）。不持久化，重启归零。 */
    private final Map<String, Double> purchaseEma = new HashMap<>();

    /** 漂移乘数表（itemId → drift ∈ [minFactor, maxFactor]，缺省 1.0）。不持久化，重启由 cleanupTask 重算。
     *  ConcurrentHashMap：异步 recomputeDrift 写 + 主线程 getEffectiveStandardPrice 读，需线程安全。 */
    private final Map<String, Double> priceDrift = new ConcurrentHashMap<>();

    /** EMA 平滑系数（新结果贡献 30%，历史 70%）。 */
    private static final double EMA_ALPHA = 0.3;

    /** 每日购买日志清理任务（异步），stop() 时取消。 */
    private BukkitTask cleanupTask;

    /** 物品标准价信息。priceSensitivity 为 -1 时用全局默认。 */
    public record ItemInfo(String itemId, double standardPrice, double priceSensitivity) {}

    public MarketManager(WooSimMarket plugin, ConfigLoader configLoader, PurchaseLogDao purchaseLogDao) {
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.purchaseLogDao = purchaseLogDao;
    }

    /**
     * 启动市场系统：加载 items.yml 并调度每日购买日志清理任务。
     */
    public void start() {
        loadItems();
        // 每日一次异步：先清理过期日志，再重算漂移（24h = 24×60×60×20 ticks）
        // 初始延迟 0：启动后立即异步执行首次 recompute（跨重启连续，主线程 0 阻塞）
        cleanupTask = TaskUtil.runAsyncAtFixed(plugin,
                () -> { cleanupPurchaseLog(); recomputeDrift(); },
                0L, 24L * 60 * 60 * 20);
    }

    /**
     * 停止市场系统：取消每日购买日志清理任务。
     */
    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /**
     * 重载 items.yml 物品标准价表（clear + 重新加载，确保已删除物品不再残留）。
     *
     * <p>主线程调用；与异步 recomputeDrift 遍历并发，{@link ConcurrentHashMap} 保证线程安全。
     * purchaseEma 与 priceDrift 不重载（运行时状态，跨 reload 连续）。</p>
     */
    public void reload() {
        itemInfos.clear();
        loadItems();
    }

    /**
     * 立即异步重算所有物品的漂移乘数（手动触发，供 {@code /wsm drift recompute} 调用）。
     *
     * <p>异步执行 {@link #recomputeDrift}，主线程不阻塞。
     * drift.enabled=false 时为空操作。频繁调用无副作用（幂等重算）。</p>
     */
    public void recomputeDriftNow() {
        TaskUtil.runAsync(plugin, this::recomputeDrift);
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
     * 获取物品的有效标准价（含漂移）。
     *
     * <p>{@code effectiveStandardPrice = standardPrice × priceDrift}，供 {@link PurchaseFormula}
     * 的 budget 硬门与 priceFactor 基准使用。drift 缺省 1.0（等价 standardPrice）。
     * drift.enabled=false 时直接返回 standardPrice（强制契约：禁用即等价未引入，无视 map 残留值）。</p>
     *
     * @param itemId 物品 ID
     * @return 有效标准价
     */
    public double getEffectiveStandardPrice(String itemId) {
        if (!configLoader.isDriftEnabled()) {
            return getStandardPrice(itemId);
        }
        return getStandardPrice(itemId) * priceDrift.getOrDefault(itemId, 1.0);
    }

    /**
     * 获取物品的当前漂移乘数（供 GUI 显示箭头与 debug 日志）。
     *
     * <p>drift.enabled=false 时返回 1.0（强制契约：禁用即等价未引入）。</p>
     *
     * @param itemId 物品 ID
     * @return drift 乘数，缺省 1.0
     */
    public double getPriceDrift(String itemId) {
        return configLoader.isDriftEnabled() ? priceDrift.getOrDefault(itemId, 1.0) : 1.0;
    }

    /**
     * 获取物品标准价表的不可变视图（供 PriceTableGui 只读遍历）。
     *
     * @return itemId → ItemInfo 的不可变映射
     */
    public Map<String, ItemInfo> getItemInfos() {
        return Collections.unmodifiableMap(itemInfos);
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
     * 记录 NPC 购买判定结果，更新该物品的动量 EMA 并异步写入 purchase_log。
     *
     * <p>EMA 更新（主线程）：{@code ema = ema × (1 − α) + outcome × α}（α=0.3）。
     * 购买 outcome=1.0，不买 outcome=0.0。purchase_log 写入通过
     * {@link TaskUtil#runAsync} 投递到异步线程，避免阻塞主线程。
     * id 字段传 0（DAO 忽略，由数据库自增）。</p>
     *
     * @param shopId      商店 id
     * @param itemId      物品 ID
     * @param price       成交价（判定时的货架价格）
     * @param bought      true=NPC 购买，false=未购买（判定耗尽）
     * @param personality NPC 性格枚举名
     */
    public void recordPurchaseOutcome(String shopId, String itemId, double price, boolean bought, String personality) {
        double outcome = bought ? 1.0 : 0.0;
        double current = purchaseEma.getOrDefault(itemId, 0.5);
        purchaseEma.put(itemId, current * (1.0 - EMA_ALPHA) + outcome * EMA_ALPHA);

        TaskUtil.runAsync(plugin, () -> purchaseLogDao.insert(
                new DatabaseManager.PurchaseLogRecord(0, shopId, itemId, price, bought, personality, System.currentTimeMillis())));
    }

    /**
     * 清理过期购买日志（每日异步执行）。
     *
     * <p>删除时间戳早于 {@code now - retentionDays × 86400000ms} 的记录，
     * retentionDays 来自 {@link ConfigLoader#getStatsRetentionDays()}（默认 7，下限 1）。
     * 在方法内读取配置而非字段缓存，便于 reload 后立即生效。</p>
     */
    private void cleanupPurchaseLog() {
        long retentionDays = configLoader.getStatsRetentionDays();
        purchaseLogDao.deleteOlderThan(System.currentTimeMillis() - retentionDays * 86400000L);
    }

    /**
     * 重算所有物品的漂移乘数（异步调用，主线程不阻塞）。
     *
     * <p>遍历 itemInfos，对每个 itemId 查询近 window-days 天 purchase_log 记录，
     * 调用 {@link DriftCalculator#computeDrift} 更新 priceDrift。
     * drift.enabled=false 时直接 return（drift 恒为 1.0）。空样本跳过（drift 不变）。</p>
     */
    private void recomputeDrift() {
        if (!configLoader.isDriftEnabled()) {
            return;
        }
        ConfigLoader.DriftConfig cfg = configLoader.getDriftConfig();
        // snapshot keySet，避免异步遍历期间主线程 reload 修改 map 抛 ConcurrentModificationException
        String[] itemIds;
        synchronized (itemInfos) {
            itemIds = itemInfos.keySet().toArray(new String[0]);
        }
        for (String itemId : itemIds) {
            List<DatabaseManager.PurchaseLogRecord> records = purchaseLogDao.findRecentByItem(itemId, cfg.windowDays());
            if (records.isEmpty()) {
                continue; // 空样本跳过，drift 保持当前值
            }
            double stdPrice = getStandardPrice(itemId);
            double currentDrift = priceDrift.getOrDefault(itemId, 1.0);
            double newDrift = DriftCalculator.computeDrift(currentDrift, stdPrice, records, cfg);
            priceDrift.put(itemId, newDrift);
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
            // per-item 敏感度覆盖（-1 表示用全局默认）
            double priceSensitivity = itemSection.getDouble("price-sensitivity", -1.0);
            itemInfos.put(itemId, new ItemInfo(itemId, standardPrice, priceSensitivity));
        }

        plugin.getLogger().info(() -> "items.yml 加载完成: " + itemInfos.size() + " 个物品");
    }
}
