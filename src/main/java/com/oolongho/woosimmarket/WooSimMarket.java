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
import com.oolongho.woosimmarket.listener.PlayerListener;
import com.oolongho.woosimmarket.market.MarketManager;
import com.oolongho.woosimmarket.npc.NpcManager;
import com.oolongho.woosimmarket.npc.NpcPacketSender;
import com.oolongho.woosimmarket.npc.NpcSkinCache;
import com.oolongho.woosimmarket.shop.PricingManager;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.command.MainCommand;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * WooSimMarket 模拟商店插件主类。
 *
 * <p>装配顺序：ConfigLoader → Messages → DatabaseManager → DAOs →
 * CraftEngineHook → VaultHook → ShopManager（加载 DB 数据）→
 * PricingManager → EconomyManager → MarketManager（加载 items.yml + 启动桶滚动）→
 * NpcSkinCache（加载缓存 + 异步预加载）→ NpcPacketSender → NpcManager → 注册监听器 →
 * 命令系统 → PlaceholderAPI 钩子。</p>
 *
 * <p>硬依赖 CraftEngine 不可用时禁用插件；软依赖 Vault/PlaceholderAPI 不可用时降级运行。</p>
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
    private NpcManager npcManager;

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

        // 5. CraftEngine 钩子（硬依赖）
        craftEngineHook = new CraftEngineHook(this);
        if (!craftEngineHook.init()) {
            getLogger().severe("CraftEngine 不可用，插件将禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 6. Vault 钩子（软依赖，降级运行）
        vaultHook = new VaultHook(this);
        vaultHook.init();

        // 7. ShopManager（加载 DB 数据到内存）
        shopManager = new ShopManager(this, shopDao, shelfDao, craftEngineHook);
        shopManager.loadAll();

        // 8. PricingManager
        pricingManager = new PricingManager(shopManager, messages);

        // 9. EconomyManager
        economyManager = new EconomyManager(vaultHook, shopManager);

        // 10. 动态市场（72 桶滑动窗口调价，NpcManager 购买判定依赖此）
        marketManager = new MarketManager(this, configLoader);
        marketManager.start();

        // 11. NPC 系统（纯发包，依赖 ShopManager + MarketManager 数据）
        npcPacketSender = new NpcPacketSender();
        npcSkinCache = new NpcSkinCache(this, configLoader.getSkinCacheFile(),
                configLoader.getSkinFetchTimeoutSeconds());
        npcSkinCache.load();
        npcSkinCache.preloadAsync(configLoader.getSkinNames());
        npcManager = new NpcManager(this, shopManager, npcPacketSender, configLoader, messages,
                npcSkinCache, marketManager);
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
        // 关闭动态市场（取消桶滚动任务）
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
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                new BlockListener(this, shopManager, craftEngineHook, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new ShelfGuiListener(this, shopManager, pricingManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new ShopGuiListener(economyManager, messages), this);
        Bukkit.getPluginManager().registerEvents(
                new ChatListener(this, pricingManager), this);
        Bukkit.getPluginManager().registerEvents(
                new PlayerListener(npcPacketSender), this);
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
}
