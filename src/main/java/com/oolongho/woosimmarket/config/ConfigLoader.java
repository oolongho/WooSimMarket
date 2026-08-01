package com.oolongho.woosimmarket.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;

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
    /** 市场配置（独立文件 market.yml，含 market/shop/shelf-display/shop-display）。 */
    private FileConfiguration marketConfig;
    /** NPC 配置（独立文件 npc.yml，含 npc/skin/thought-display/personalities）。 */
    private FileConfiguration npcConfig;

    // settings
    private boolean debugPurchase;
    private boolean debugPathfinding;
    private boolean debugGeneral;
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
    private int npcDeliberationMaxRolls;
    private int npcDeliberationIntervalMinTicks;
    private int npcDeliberationIntervalMaxTicks;
    /** 生成系数：NPC 数 = min(max-concurrent, 启用货架数 × spawn-factor)。 */
    private double npcSpawnFactor;
    /** 换架概率（每次判定未命中后 roll）。 */
    private double shelfSwitchProbability;
    /** 换架超时秒数（超时后传送至目标）。 */
    private int switchTimeoutSeconds;

    // market
    private double marketSensitivity;
    private double marketGlobalMultiplier;
    private double marketTimeStrength;
    private double marketMomentumStrength;

    // skin
    private List<String> skinNames;
    private String skinCacheFile;
    private int skinFetchTimeoutSeconds;
    private String skinFallback;

    // database
    private String databaseFile;

    // thought-display（头顶思考展示，子系统 4）
    private boolean thoughtDisplayEnabled;
    private double thoughtDisplayYOffset;
    private int thoughtDisplayFlashDurationTicks;
    private Display.Billboard thoughtDisplayBillboard;
    private Color thoughtDisplayBackgroundColor;
    private boolean thoughtDisplayShadow;
    private boolean thoughtDisplaySeeThrough;

    // shop-display（收银台全息展示）
    private boolean shopDisplayEnabled;
    private double shopDisplayHeadYOffset;
    private double shopDisplayNameYOffset;
    private String shopDisplayTextColor;

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
        loadNpcConfig();
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
     * 加载 npc.yml（首次不存在则释放默认文件）。
     *
     * <p>风格与 {@link #loadMarketConfig} 一致：先 {@link File#exists()} 检查再
     * {@code saveResource}，避免重复释放时的警告日志。</p>
     *
     * <p>合并自原 personalities.yml（NPC/skin/thought-display/personalities 四节），
     * 若检测到旧 personalities.yml 文件则提示管理员删除。</p>
     */
    private void loadNpcConfig() {
        File npcFile = new File(plugin.getDataFolder(), "npc.yml");
        if (!npcFile.exists()) {
            plugin.saveResource("npc.yml", false);
        }
        npcConfig = YamlConfiguration.loadConfiguration(npcFile);

        // 旧文件检测：personalities.yml 已合并到 npc.yml
        File oldFile = new File(plugin.getDataFolder(), "personalities.yml");
        if (oldFile.exists()) {
            plugin.getLogger().warning("旧文件 personalities.yml 已合并到 npc.yml，可安全删除。");
        }
    }

    /**
     * 集中加载所有配置键，并对数值型参数执行下限兜底，避免管理员误配导致逻辑异常。
     */
    private void loadValues() {
        // settings
        debugPurchase = config.getBoolean("settings.debug.purchase", false);
        debugPathfinding = config.getBoolean("settings.debug.pathfinding", false);
        debugGeneral = config.getBoolean("settings.debug.general", false);
        language = config.getString("settings.language", "zh-CN");

        // shop（从 market.yml 读取）
        shopBindRadius = Math.max(1, marketConfig.getInt("shop.bind-radius", 16));
        shopLimit = Math.max(1, marketConfig.getInt("shop.limit", 1));
        shopMinDistance = Math.max(0.0, marketConfig.getDouble("shop.min-distance", 8.0));

        // npc（从 npc.yml 读取）
        npcSpawnIntervalMin = Math.max(1, npcConfig.getInt("npc.spawn-interval-min", 30));
        // 保证 max >= min，避免随机区间退化
        npcSpawnIntervalMax = Math.max(npcSpawnIntervalMin, npcConfig.getInt("npc.spawn-interval-max", 60));
        npcMaxConcurrent = Math.max(1, npcConfig.getInt("npc.max-concurrent", 3));
        npcStuckThresholdSeconds = Math.max(1, npcConfig.getInt("npc.stuck-threshold-seconds", 15));
        npcStuckThresholdDistance = Math.max(0.1, npcConfig.getDouble("npc.stuck-threshold-distance", 5.0));
        npcDespawnDistance = Math.max(1.0, npcConfig.getDouble("npc.despawn-distance", 32.0));
        npcTargetReachDistance = Math.max(0.1, npcConfig.getDouble("npc.target-reach-distance", 1.5));
        // YAML 不支持 0xFF 字面量，配置文件用十进制 255；此处 0xFF 仅作 getInt 默认值
        npcSkinParts = npcConfig.getInt("npc.skin-parts", 0xFF);
        pathfindingAvoidHazards = npcConfig.getBoolean("npc.pathfinding-avoid-hazards", true);

        // npc.deliberation（徘徊判定，子系统 3）
        npcDeliberationMaxRolls = Math.max(1, npcConfig.getInt("npc.deliberation.max-rolls", 5));
        npcDeliberationIntervalMinTicks = Math.max(1, npcConfig.getInt("npc.deliberation.interval-min-ticks", 20));
        // 保证 max >= min，避免区间退化
        npcDeliberationIntervalMaxTicks = Math.max(npcDeliberationIntervalMinTicks,
                npcConfig.getInt("npc.deliberation.interval-max-ticks", 60));

        // npc.spawn / npc.deliberation（换架行为，子系统 1.5）
        npcSpawnFactor = Math.max(0.1, npcConfig.getDouble("npc.spawn-factor", 1.0));
        shelfSwitchProbability = Math.max(0.0, Math.min(1.0,
                npcConfig.getDouble("npc.deliberation.shelf-switch-probability", 0.3)));
        switchTimeoutSeconds = Math.max(1, npcConfig.getInt("npc.deliberation.switch-timeout-seconds", 3));

        // market（从独立文件 market.yml 读取）
        marketSensitivity = Math.max(0.1, marketConfig.getDouble("market.sensitivity", 2.0));
        marketGlobalMultiplier = Math.max(0.0, marketConfig.getDouble("market.global-multiplier", 1.0));
        marketTimeStrength = Math.max(0.0, marketConfig.getDouble("market.time-strength", 1.0));
        marketMomentumStrength = Math.max(0.0, marketConfig.getDouble("market.momentum-strength", 0.3));

        // skin（从 npc.yml 读取）
        skinNames = npcConfig.getStringList("skin.names");
        if (skinNames.isEmpty()) {
            skinNames = new ArrayList<>(List.of("Notch", "jeb_"));
        }
        skinCacheFile = npcConfig.getString("skin.cache-file", "data/skins.json");
        skinFetchTimeoutSeconds = Math.max(1, npcConfig.getInt("skin.fetch-timeout-seconds", 10));
        skinFallback = npcConfig.getString("skin.fallback", "STEVE");

        // database
        databaseFile = config.getString("database.file", "data/woosimmarket.db");

        // thought-display（头顶思考展示，子系统 4；从 npc.yml 读取，去掉 visualization. 前缀）
        thoughtDisplayEnabled = npcConfig.getBoolean("thought-display.enabled", true);
        thoughtDisplayYOffset = Math.max(0.0, npcConfig.getDouble("thought-display.y-offset", 2.3));
        thoughtDisplayFlashDurationTicks = Math.max(1, npcConfig.getInt("thought-display.flash-duration-ticks", 40));
        try {
            thoughtDisplayBillboard = Display.Billboard.valueOf(
                    npcConfig.getString("thought-display.billboard", "VERTICAL").toUpperCase());
        } catch (IllegalArgumentException ex) {
            thoughtDisplayBillboard = Display.Billboard.VERTICAL;
        }
        thoughtDisplayBackgroundColor = parseBackgroundColor(
                npcConfig.getString("thought-display.background-color", "0,0,0,64"));
        thoughtDisplayShadow = npcConfig.getBoolean("thought-display.shadow", true);
        thoughtDisplaySeeThrough = npcConfig.getBoolean("thought-display.see-through", false);

        // shop-display（收银台全息展示；从 market.yml 读取，去掉 visualization. 前缀）
        shopDisplayEnabled = marketConfig.getBoolean("shop-display.enabled", true);
        shopDisplayHeadYOffset = Math.max(0.0, marketConfig.getDouble("shop-display.head-y-offset", 1.5));
        shopDisplayNameYOffset = Math.max(0.0, marketConfig.getDouble("shop-display.name-y-offset", 1.2));
        shopDisplayTextColor = marketConfig.getString("shop-display.text-color", "#a3b547");
    }

    /**
     * 解析背景色 RGBA 字符串（如 "0,0,0,64"）为 Bukkit Color。
     *
     * <p>格式：r,g,b,a（0-255，逗号分隔）。解析失败时兜底半透明黑底。</p>
     *
     * @param value 原始字符串
     * @return Bukkit Color（ARGB）
     */
    private Color parseBackgroundColor(String value) {
        try {
            String[] parts = value.split(",");
            int r = clamp255(Integer.parseInt(parts[0].trim()));
            int g = clamp255(Integer.parseInt(parts[1].trim()));
            int b = clamp255(Integer.parseInt(parts[2].trim()));
            int a = clamp255(Integer.parseInt(parts[3].trim()));
            return Color.fromARGB(a, r, g, b);
        } catch (Exception ex) {
            return Color.fromARGB(64, 0, 0, 0);
        }
    }

    private int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * 重载配置：重新读取 config.yml 并刷新所有字段。
     */
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        loadMarketConfig();
        loadNpcConfig();
        loadValues();
    }

    // ===== Getter =====

    public boolean isDebugPurchase() {
        return debugPurchase;
    }

    public boolean isDebugPathfinding() {
        return debugPathfinding;
    }

    public boolean isDebugGeneral() {
        return debugGeneral;
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
        return marketConfig.getString("shop.cash-register-block", "simmarket:cash_register");
    }

    /**
     * 货架方块 ID（CraftEngine 格式 namespace:path，或原版 Material 名）。
     *
     * @return 方块 ID 字符串
     */
    public String getShelfBlock() {
        return marketConfig.getString("shop.shelf-block", "simmarket:shelf");
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

    /** NPC 徘徊判定最大次数（impatience=0 时的判定次数，也是所有性格硬上限）。 */
    public int getNpcDeliberationMaxRolls() {
        return npcDeliberationMaxRolls;
    }

    /** NPC 徘徊判定间隔下限（impatience=1 时，ticks；20t=1s）。 */
    public int getNpcDeliberationIntervalMinTicks() {
        return npcDeliberationIntervalMinTicks;
    }

    /** NPC 徘徊判定间隔上限（impatience=0 时，ticks）。 */
    public int getNpcDeliberationIntervalMaxTicks() {
        return npcDeliberationIntervalMaxTicks;
    }

    /** 生成系数：NPC 数 = min(max-concurrent, 启用货架数 × spawn-factor)。 */
    public double getNpcSpawnFactor() {
        return npcSpawnFactor;
    }

    /** 换架概率（每次判定未命中后 roll）。 */
    public double getShelfSwitchProbability() {
        return shelfSwitchProbability;
    }

    /** 换架超时秒数（超时后传送至目标）。 */
    public int getSwitchTimeoutSeconds() {
        return switchTimeoutSeconds;
    }

    /** NPC 随机装备是否启用。 */
    public boolean isNpcEquipmentEnabled() {
        return npcConfig.getBoolean("npc.equipment.enabled", true);
    }

    /** 胸甲装备池（Material 名列表）。 */
    public List<String> getEquipmentChestplate() {
        return npcConfig.getStringList("npc.equipment.chestplate");
    }

    /** 护腿装备池（Material 名列表）。 */
    public List<String> getEquipmentLeggings() {
        return npcConfig.getStringList("npc.equipment.leggings");
    }

    /** 靴子装备池（Material 名列表）。 */
    public List<String> getEquipmentBoots() {
        return npcConfig.getStringList("npc.equipment.boots");
    }

    /** 主手装饰物池（Material 名列表，AIR=空手）。 */
    public List<String> getEquipmentMainHand() {
        return npcConfig.getStringList("npc.equipment.main-hand");
    }

    /** 皮革染色颜色池（解析十六进制字符串为 Bukkit Color）。 */
    public List<org.bukkit.Color> getLeatherColors() {
        List<org.bukkit.Color> colors = new ArrayList<>();
        for (String hex : npcConfig.getStringList("npc.equipment.leather-colors")) {
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

    public double getMarketGlobalMultiplier() {
        return marketGlobalMultiplier;
    }

    public double getMarketTimeStrength() {
        return marketTimeStrength;
    }

    /** 购买动量因子强度（marketFactor 幅度，0=禁用动量，0.3=默认）。 */
    public double getMarketMomentumStrength() {
        return marketMomentumStrength;
    }

    /**
     * 获取 npc.yml 配置（含 npc/skin/thought-display/personalities 四节）。
     *
     * <p>由 {@link com.oolongho.woosimmarket.npc.PersonalityManager} 在 load/reload 时读取
     * personalities 节，仅返回原始 {@link FileConfiguration}，性格解析与钳制由 PersonalityManager 负责。</p>
     *
     * @return npc.yml 配置
     */
    public FileConfiguration getNpcConfig() {
        return npcConfig;
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
        return npcConfig.getDouble("npc.speed", 0.15);
    }

    /** A* 寻路最大搜索距离（方块）。 */
    public int getNpcPathfindingMaxDistance() {
        return npcConfig.getInt("npc.pathfinding-max-distance", 32);
    }

    /** A* 寻路最大迭代节点数。 */
    public int getNpcPathfindingMaxIterations() {
        return npcConfig.getInt("npc.pathfinding-max-iterations", 5000);
    }

    /**
     * 货架方块上方全息展示是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isShelfDisplayEnabled() {
        return marketConfig.getBoolean("shelf-display.enabled", true);
    }

    /**
     * 货架物品图标 Y 偏移（相对货架方块底面）。
     *
     * @return Y 偏移
     */
    public double getShelfDisplayItemOffsetY() {
        return marketConfig.getDouble("shelf-display.item-offset-y", 1.2);
    }

    /**
     * 商店范围粒子圆环持续秒数。
     *
     * @return 持续秒数
     */
    public int getRangeDurationSeconds() {
        return marketConfig.getInt("range-duration-seconds", 10);
    }

    /** 头顶思考展示是否启用。 */
    public boolean isThoughtDisplayEnabled() {
        return thoughtDisplayEnabled;
    }

    /** TextDisplay 相对 NPC 脚位的 Y 偏移（格）。 */
    public double getThoughtDisplayYOffset() {
        return thoughtDisplayYOffset;
    }

    /** BUY/GIVE_UP 结果文本停留时长（ticks）。 */
    public int getThoughtDisplayFlashDurationTicks() {
        return thoughtDisplayFlashDurationTicks;
    }

    /** TextDisplay 朝向模式（默认 VERTICAL：仅水平旋转，垂直固定）。 */
    public Display.Billboard getThoughtDisplayBillboard() {
        return thoughtDisplayBillboard;
    }

    /** TextDisplay 背景色（ARGB）。 */
    public Color getThoughtDisplayBackgroundColor() {
        return thoughtDisplayBackgroundColor;
    }

    /** TextDisplay 文本阴影。 */
    public boolean isThoughtDisplayShadow() {
        return thoughtDisplayShadow;
    }

    /** TextDisplay 是否穿透方块遮挡。 */
    public boolean isThoughtDisplaySeeThrough() {
        return thoughtDisplaySeeThrough;
    }

    /** 收银台全息展示是否启用。 */
    public boolean isShopDisplayEnabled() {
        return shopDisplayEnabled;
    }

    /** 店主头颅 Y 偏移（相对收银台方块底面）。 */
    public double getShopDisplayHeadYOffset() {
        return shopDisplayHeadYOffset;
    }

    /** 店名文字 Y 偏移（相对收银台方块底面）。 */
    public double getShopDisplayNameYOffset() {
        return shopDisplayNameYOffset;
    }

    /** 店名文字颜色（十六进制字符串）。 */
    public String getShopDisplayTextColor() {
        return shopDisplayTextColor;
    }

    /** 商店统计交易记录保留天数（默认 7，下限 1）。 */
    public int getStatsRetentionDays() {
        return Math.max(1, config.getInt("stats.retention-days", 7));
    }

    /** 商店统计面板单次查询记录上限（默认 100，下限 1）。 */
    public int getStatsQueryLimit() {
        return Math.max(1, config.getInt("stats.query-limit", 100));
    }
}
