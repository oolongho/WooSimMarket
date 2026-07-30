package com.oolongho.woosimmarket.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

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
    /** 市场配置（独立文件 market.yml）。 */
    private FileConfiguration marketConfig;

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
    private int npcSkinParts;
    private boolean pathfindingAvoidHazards;

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
        loadMarketConfig();
        loadValues();
    }

    /**
     * 加载 market.yml（首次不存在则释放默认文件）。
     */
    private void loadMarketConfig() {
        File marketFile = new File(plugin.getDataFolder(), "market.yml");
        if (!marketFile.exists()) {
            plugin.saveResource("market.yml", false);
        }
        marketConfig = YamlConfiguration.loadConfiguration(marketFile);
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
        // YAML 不支持 0xFF 字面量，配置文件用十进制 255；此处 0xFF 仅作 getInt 默认值
        npcSkinParts = config.getInt("npc.skin-parts", 0xFF);
        pathfindingAvoidHazards = config.getBoolean("npc.pathfinding-avoid-hazards", true);

        // market（从独立文件 market.yml 读取）
        marketSensitivity = Math.max(0.1, marketConfig.getDouble("market.sensitivity", 2.0));
        marketMultiplierMin = Math.max(0.001, marketConfig.getDouble("market.multiplier-min", 0.1));
        // 保证 max >= min
        marketMultiplierMax = Math.max(marketMultiplierMin, marketConfig.getDouble("market.multiplier-max", 5.0));
        marketMultiplierExponent = Math.max(0.0, marketConfig.getDouble("market.multiplier-exponent", 0.8));
        marketBucketCount = Math.max(1, marketConfig.getInt("market.bucket-count", 72));
        marketBucketMinutes = Math.max(1, marketConfig.getInt("market.bucket-minutes", 5));
        marketGlobalMultiplier = Math.max(0.0, marketConfig.getDouble("market.global-multiplier", 1.0));

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
        loadMarketConfig();
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

    /**
     * 收银台方块 ID（CraftEngine 格式 namespace:path，或原版 Material 名）。
     *
     * @return 方块 ID 字符串
     */
    public String getCashRegisterBlock() {
        return config.getString("shop.cash-register-block", "simmarket:cash_register");
    }

    /**
     * 货架方块 ID（CraftEngine 格式 namespace:path，或原版 Material 名）。
     *
     * @return 方块 ID 字符串
     */
    public String getShelfBlock() {
        return config.getString("shop.shelf-block", "simmarket:shelf");
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

    /** NPC 皮肤外层显示位掩码（0x01 Cape | 0x02 Jacket | 0x04 LSleeve | 0x08 RSleeve | 0x10 LPants | 0x20 RPants | 0x40 Hat）。 */
    public int getNpcSkinParts() {
        return npcSkinParts;
    }

    /** 寻路是否规避危险方块（火/岩浆/仙人掌等）。 */
    public boolean isPathfindingAvoidHazards() {
        return pathfindingAvoidHazards;
    }

    /** NPC 随机装备是否启用。 */
    public boolean isNpcEquipmentEnabled() {
        return config.getBoolean("npc.equipment.enabled", true);
    }

    /** 胸甲装备池（Material 名列表）。 */
    public List<String> getEquipmentChestplate() {
        return config.getStringList("npc.equipment.chestplate");
    }

    /** 护腿装备池（Material 名列表）。 */
    public List<String> getEquipmentLeggings() {
        return config.getStringList("npc.equipment.leggings");
    }

    /** 靴子装备池（Material 名列表）。 */
    public List<String> getEquipmentBoots() {
        return config.getStringList("npc.equipment.boots");
    }

    /** 主手装饰物池（Material 名列表，AIR=空手）。 */
    public List<String> getEquipmentMainHand() {
        return config.getStringList("npc.equipment.main-hand");
    }

    /** 皮革染色颜色池（解析十六进制字符串为 Bukkit Color）。 */
    public List<org.bukkit.Color> getLeatherColors() {
        List<org.bukkit.Color> colors = new ArrayList<>();
        for (String hex : config.getStringList("npc.equipment.leather-colors")) {
            try {
                colors.add(org.bukkit.Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)));
            } catch (Exception ignored) {
                // 无效颜色跳过
            }
        }
        return colors;
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

    /** NPC 移动速度（格/tick）。 */
    public double getNpcSpeed() {
        return config.getDouble("npc.speed", 0.15);
    }

    /** A* 寻路最大搜索距离（方块）。 */
    public int getNpcPathfindingMaxDistance() {
        return config.getInt("npc.pathfinding-max-distance", 32);
    }

    /** A* 寻路最大迭代节点数。 */
    public int getNpcPathfindingMaxIterations() {
        return config.getInt("npc.pathfinding-max-iterations", 5000);
    }

    /**
     * 货架方块上方全息展示是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isShelfDisplayEnabled() {
        return config.getBoolean("visualization.shelf-display.enabled", true);
    }

    /**
     * 货架物品图标 Y 偏移（相对货架方块底面）。
     *
     * @return Y 偏移
     */
    public double getShelfDisplayItemOffsetY() {
        return config.getDouble("visualization.shelf-display.item-offset-y", 1.2);
    }

    /**
     * 商店范围粒子圆环持续秒数。
     *
     * @return 持续秒数
     */
    public int getRangeDurationSeconds() {
        return config.getInt("visualization.range-duration-seconds", 10);
    }
}
