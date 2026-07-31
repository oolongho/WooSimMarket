package com.oolongho.woosimmarket;

import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.database.DatabaseManager;
import com.oolongho.woosimmarket.database.ShelfDao;
import com.oolongho.woosimmarket.database.ShopDao;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.gui.ShelfGui;
import com.oolongho.woosimmarket.gui.ShelfGuiListener;
import com.oolongho.woosimmarket.gui.ShopGui;
import com.oolongho.woosimmarket.gui.ShopGuiListener;
import com.oolongho.woosimmarket.hook.CraftEngineHook;
import com.oolongho.woosimmarket.hook.PlaceholderAPIHook;
import com.oolongho.woosimmarket.hook.VaultHook;
import com.oolongho.woosimmarket.listener.BlockListener;
import com.oolongho.woosimmarket.listener.ChatListener;
import com.oolongho.woosimmarket.listener.ChunkListener;
import com.oolongho.woosimmarket.listener.PlayerListener;
import com.oolongho.woosimmarket.market.MarketManager;
import com.oolongho.woosimmarket.market.PurchaseFormula;
import com.oolongho.woosimmarket.npc.NpcManager;
import com.oolongho.woosimmarket.npc.NpcPacketSender;
import com.oolongho.woosimmarket.npc.NpcSkinCache;
import com.oolongho.woosimmarket.npc.PersonalityManager;
import com.oolongho.woosimmarket.shop.PricingManager;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import com.oolongho.woosimmarket.visualize.ShopDisplayManager;
import com.oolongho.woosimmarket.visualize.ShopRangeVisualizer;
import com.oolongho.woosimmarket.visualize.ThoughtDisplayManager;
import com.oolongho.woosimmarket.command.MainCommand;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * WooSimMarket 模拟商店插件主类。
 *
 * <p>装配顺序：ConfigLoader → Messages → DatabaseManager → DAOs →
 * CraftEngineHook → VaultHook → ShopManager（加载 DB 数据）→
 * PricingManager → EconomyManager → MarketManager（加载 items.yml）→
 * NpcSkinCache（加载缓存 + 异步预加载）→ NpcPacketSender → PurchaseFormula → NpcManager → 注册监听器 →
 * 命令系统 → PlaceholderAPI 钩子。</p>
 *
 * <p>软依赖 CraftEngine/Vault/PlaceholderAPI，任一不可用时降级运行：
 * CraftEngine 缺失则用原版方块模式（cash-register/shelf 配置兜底为 EMERALD_BLOCK/CHISELED_BOOKSHELF）。</p>
 *
 * @author oolongho
 */
public class WooSimMarket extends JavaPlugin {

    private static WooSimMarket instance;

    // 配置与消息
    private ConfigLoader configLoader;
    private Messages messages;

    // 数据库
    private DatabaseManager databaseManager;
    private ShopDao shopDao;
    private ShelfDao shelfDao;

    // 钩子
    private CraftEngineHook craftEngineHook;
    private VaultHook vaultHook;
    private PlaceholderAPIHook placeholderApiHook;

    // 业务管理器
    private ShopManager shopManager;
    private PricingManager pricingManager;
    private EconomyManager economyManager;
    private MarketManager marketManager;
    private NpcPacketSender npcPacketSender;
    private NpcSkinCache npcSkinCache;
    private PersonalityManager personalityManager;
    private NpcManager npcManager;
    private ShelfDisplayManager shelfDisplayManager;
    private ShopDisplayManager shopDisplayManager;
    private ThoughtDisplayManager thoughtDisplayManager;
    private ShopRangeVisualizer shopRangeVisualizer;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 配置
        configLoader = new ConfigLoader(this);
        configLoader.initialize();
        getLogger().info("配置加载完成");

        // 2. 消息
        messages = new Messages(this);
        messages.initialize();

        // 3. 数据库
        try {
            databaseManager = new DatabaseManager(this, configLoader.getDatabaseFile());
            databaseManager.init();
            getLogger().info("数据库连接成功");
        } catch (SQLException e) {
            getLogger().severe(() -> "数据库初始化失败，插件将禁用: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. DAO
        shopDao = new ShopDao(databaseManager);
        shelfDao = new ShelfDao(databaseManager);

        // 5. CraftEngine 钩子（软依赖：未安装时降级为原版方块模式）
        craftEngineHook = new CraftEngineHook(this, configLoader);
        craftEngineHook.init();

        // 6. Vault 钩子（软依赖，降级运行）
        vaultHook = new VaultHook(this);
        vaultHook.init();

        // 7. ShopManager（加载 DB 数据到内存）
        shopManager = new ShopManager(this, shopDao, shelfDao, craftEngineHook);
        shopManager.loadAll();

        // 7.5. 可视化管理器（依赖 ShopManager 内存数据，需在 loadAll 之后初始化）
        shelfDisplayManager = new ShelfDisplayManager(this, shopManager, configLoader);
        shelfDisplayManager.init();
        // 7.6. 收银台全息展示管理器（依赖 ShopManager 内存数据）
        shopDisplayManager = new ShopDisplayManager(this, shopManager, configLoader, messages);
        shopDisplayManager.init();
        shopRangeVisualizer = new ShopRangeVisualizer(this, configLoader);

        // 8. PricingManager
        pricingManager = new PricingManager(shopManager, messages);

        // 9. EconomyManager
        economyManager = new EconomyManager(vaultHook, shopManager);

        // 10. 物品价目表（加载 items.yml，NpcManager 购买判定依赖此）
        marketManager = new MarketManager(this, configLoader);
        marketManager.start();

        // 10.5. 购买判别式（纯计算，依赖 MarketManager + ConfigLoader）
        PurchaseFormula purchaseFormula = new PurchaseFormula(marketManager, configLoader);

        // 11. NPC 系统（纯发包，依赖 ShopManager + MarketManager 数据）
        npcPacketSender = new NpcPacketSender(configLoader, messages);
        npcSkinCache = new NpcSkinCache(this, configLoader.getSkinCacheFile(),
                configLoader.getSkinFetchTimeoutSeconds());
        npcSkinCache.load();
        npcSkinCache.preloadAsync(configLoader.getSkinNames());
        personalityManager = new PersonalityManager();
        personalityManager.load(configLoader.getPersonalitiesConfig());
        // 10.6. 头顶思考展示管理器（依赖 ConfigLoader + Messages，NpcManager 注入）
        thoughtDisplayManager = new ThoughtDisplayManager(this, configLoader, messages);
        npcManager = new NpcManager(this, shopManager, npcPacketSender, configLoader, messages,
                npcSkinCache, marketManager, shelfDisplayManager, personalityManager, purchaseFormula,
                thoughtDisplayManager);
        npcManager.start();

        // 12. 注册监听器
        registerListeners();

        // 13. 命令系统
        MainCommand mainCommand = new MainCommand(this);
        getCommand("woosimmarket").setExecutor(mainCommand);
        getCommand("woosimmarket").setTabCompleter(mainCommand);

        // 14. PlaceholderAPI 钩子（软依赖，降级运行 —— 必须在实例化前检查，避免 NoClassDefFoundError）
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderApiHook = new PlaceholderAPIHook(this);
            placeholderApiHook.init();
        } else {
            getLogger().info(() -> "未找到 PlaceholderAPI，占位符功能不可用");
        }

        String version = getPluginMeta().getVersion();
        getLogger().info(() -> "WooSimMarket v" + version + " 已启用!");
    }

    @Override
    public void onDisable() {
        // 注销 PlaceholderAPI 扩展（软依赖，不可用时 cleanup 为空操作）
        if (placeholderApiHook != null) {
            placeholderApiHook.cleanup();
        }
        // 关闭 NPC 系统（取消任务、销毁所有发包 NPC）
        if (npcManager != null) {
            npcManager.stop();
        }
        // 移除所有货架展示实体（优雅关闭，避免客户端残留）
        if (shelfDisplayManager != null) {
            shelfDisplayManager.clearAll();
        }
        // 移除所有收银台展示实体
        if (shopDisplayManager != null) {
            shopDisplayManager.clearAll();
        }
        // 关闭市场系统
        if (marketManager != null) {
            marketManager.stop();
        }
        // 关闭所有打开的 GUI（ShelfGui 触发 onClose 持久化商品槽物品，ShopGui 无需持久化）
        for (var player : Bukkit.getOnlinePlayers()) {
            var holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ShelfGui || holder instanceof ShopGui) {
                player.closeInventory();
            }
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("WooSimMarket 已禁用");
    }

    /**
     * 重载插件配置与消息。
     */
    public void reload() {
        configLoader.reload();
        messages.reload();
        if (personalityManager != null) {
            personalityManager.reload(configLoader.getPersonalitiesConfig());
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                new BlockListener(this, shopManager, craftEngineHook, messages,
                        shelfDisplayManager, shopDisplayManager, shopRangeVisualizer), this);
        Bukkit.getPluginManager().registerEvents(
                new ShelfGuiListener(this, shopManager, pricingManager, messages, shelfDisplayManager), this);
        Bukkit.getPluginManager().registerEvents(
                new ShopGuiListener(economyManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new ChatListener(this, pricingManager), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerListener(npcPacketSender), this);
        Bukkit.getPluginManager().registerEvents(
                new ChunkListener(shelfDisplayManager, shopDisplayManager), this);
    }

    // ===== Getter =====

    public static WooSimMarket getInstance() {
        return instance;
    }

    public ConfigLoader getConfigLoader() {
        return configLoader;
    }

    public Messages getMessages() {
        return messages;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CraftEngineHook getCraftEngineHook() {
        return craftEngineHook;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public PricingManager getPricingManager() {
        return pricingManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public NpcPacketSender getNpcPacketSender() {
        return npcPacketSender;
    }

    public PersonalityManager getPersonalityManager() {
        return personalityManager;
    }

    public ShelfDisplayManager getShelfDisplayManager() {
        return shelfDisplayManager;
    }

    public ShopDisplayManager getShopDisplayManager() {
        return shopDisplayManager;
    }

    public ShopRangeVisualizer getShopRangeVisualizer() {
        return shopRangeVisualizer;
    }
}
