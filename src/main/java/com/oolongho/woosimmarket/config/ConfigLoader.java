package com.oolongho.woosimmarket.config;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import com.oolongho.woosimmarket.WooSimMarket;

/**
 * 配置加载器
 * 负责加载和管理插件 config.yml 配置
 *
 * <p>风格参考 WooHolograms ConfigManager：私有字段 + {@link #loadValues()} 集中加载 +
 * {@code Math.max} 下限兜底 + getter 集中。字段精确对应 config.yml 所有键，不多不少。</p>
 *
 * @author oolongho
 */
public class ConfigLoader {

    private final WooSimMarket plugin;
    private FileConfiguration config;

    // settings
    private boolean debug;
    private String language;

    // shop
    private int shopBindRadius;
    private int shopLimit;
    private double shopMinDistance;

    // npc
    private int npcSpawnIntervalMin;
    private int npcSpawnIntervalMax;
    private int npcMaxConcurrent;
    private int npcStuckThresholdSeconds;
    private double npcStuckThresholdDistance;
    private double npcDespawnDistance;
    private double npcTargetReachDistance;

    // market
    private double marketSensitivity;
    private double marketMultiplierMin;
    private double marketMultiplierMax;
    private double marketMultiplierExponent;
    private int marketBucketCount;
    private int marketBucketMinutes;
    private double marketGlobalMultiplier;

    // skin
    private List<String> skinNames;
    private String skinCacheFile;
    private int skinFetchTimeoutSeconds;
    private String skinFallback;

    // database
    private String databaseFile;

    public ConfigLoader(WooSimMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化配置：释放默认 config.yml 并加载所有键。
     */
    public void initialize() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
        loadValues();
    }

    /**
     * 集中加载所有配置键，并对数值型参数执行下限兜底，避免管理员误配导致逻辑异常。
     */
    private void loadValues() {
        // settings
        debug = config.getBoolean("settings.debug", false);
        language = config.getString("settings.language", "zh-CN");

        // shop
        shopBindRadius = Math.max(1, config.getInt("shop.bind-radius", 16));
        shopLimit = Math.max(1, config.getInt("shop.limit", 1));
        shopMinDistance = Math.max(0.0, config.getDouble("shop.min-distance", 8.0));

        // npc
        npcSpawnIntervalMin = Math.max(1, config.getInt("npc.spawn-interval-min", 30));
        // 保证 max >= min，避免随机区间退化
        npcSpawnIntervalMax = Math.max(npcSpawnIntervalMin, config.getInt("npc.spawn-interval-max", 60));
        npcMaxConcurrent = Math.max(1, config.getInt("npc.max-concurrent", 3));
        npcStuckThresholdSeconds = Math.max(1, config.getInt("npc.stuck-threshold-seconds", 15));
        npcStuckThresholdDistance = Math.max(0.1, config.getDouble("npc.stuck-threshold-distance", 5.0));
        npcDespawnDistance = Math.max(1.0, config.getDouble("npc.despawn-distance", 32.0));
        npcTargetReachDistance = Math.max(0.1, config.getDouble("npc.target-reach-distance", 1.5));

        // market
        marketSensitivity = Math.max(0.1, config.getDouble("market.sensitivity", 2.0));
        marketMultiplierMin = Math.max(0.001, config.getDouble("market.multiplier-min", 0.1));
        // 保证 max >= min
        marketMultiplierMax = Math.max(marketMultiplierMin, config.getDouble("market.multiplier-max", 5.0));
        marketMultiplierExponent = Math.max(0.0, config.getDouble("market.multiplier-exponent", 0.8));
        marketBucketCount = Math.max(1, config.getInt("market.bucket-count", 72));
        marketBucketMinutes = Math.max(1, config.getInt("market.bucket-minutes", 5));
        marketGlobalMultiplier = Math.max(0.0, config.getDouble("market.global-multiplier", 1.0));

        // skin
        skinNames = config.getStringList("skin.names");
        if (skinNames.isEmpty()) {
            skinNames = new ArrayList<>(List.of("Notch", "jeb_"));
        }
        skinCacheFile = config.getString("skin.cache-file", "skins.json");
        skinFetchTimeoutSeconds = Math.max(1, config.getInt("skin.fetch-timeout-seconds", 10));
        skinFallback = config.getString("skin.fallback", "STEVE");

        // database
        databaseFile = config.getString("database.file", "woosimmarket.db");
    }

    /**
     * 重载配置：重新读取 config.yml 并刷新所有字段。
     */
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        loadValues();
    }

    // ===== Getter =====

    public boolean isDebug() {
        return debug;
    }

    public String getLanguage() {
        return language;
    }

    public int getShopBindRadius() {
        return shopBindRadius;
    }

    public int getShopLimit() {
        return shopLimit;
    }

    public double getShopMinDistance() {
        return shopMinDistance;
    }

    public int getNpcSpawnIntervalMin() {
        return npcSpawnIntervalMin;
    }

    public int getNpcSpawnIntervalMax() {
        return npcSpawnIntervalMax;
    }

    public int getNpcMaxConcurrent() {
        return npcMaxConcurrent;
    }

    public int getNpcStuckThresholdSeconds() {
        return npcStuckThresholdSeconds;
    }

    public double getNpcStuckThresholdDistance() {
        return npcStuckThresholdDistance;
    }

    public double getNpcDespawnDistance() {
        return npcDespawnDistance;
    }

    public double getNpcTargetReachDistance() {
        return npcTargetReachDistance;
    }

    public double getMarketSensitivity() {
        return marketSensitivity;
    }

    public double getMarketMultiplierMin() {
        return marketMultiplierMin;
    }

    public double getMarketMultiplierMax() {
        return marketMultiplierMax;
    }

    public double getMarketMultiplierExponent() {
        return marketMultiplierExponent;
    }

    public int getMarketBucketCount() {
        return marketBucketCount;
    }

    public int getMarketBucketMinutes() {
        return marketBucketMinutes;
    }

    public double getMarketGlobalMultiplier() {
        return marketGlobalMultiplier;
    }

    public List<String> getSkinNames() {
        return skinNames;
    }

    public String getSkinCacheFile() {
        return skinCacheFile;
    }

    public int getSkinFetchTimeoutSeconds() {
        return skinFetchTimeoutSeconds;
    }

    public String getSkinFallback() {
        return skinFallback;
    }

    public String getDatabaseFile() {
        return databaseFile;
    }
}
