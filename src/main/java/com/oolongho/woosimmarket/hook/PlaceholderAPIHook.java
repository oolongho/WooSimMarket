package com.oolongho.woosimmarket.hook;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.shop.ShopManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * PlaceholderAPI 钩子。
 *
 * <p>调用方（{@link WooSimMarket#onEnable}）须在实例化前检查 PlaceholderAPI 是否安装 ——
 * 本类继承 {@link PlaceholderExpansion}，类加载即依赖 PAPI 存在。</p>
 *
 * <p>支持的占位符（仅对在线玩家有效）：
 * <ul>
 *   <li>{@code %woosimmarket_balance%} — 玩家所有商店的余额总和（格式化为 2 位小数）</li>
 *   <li>{@code %woosimmarket_sales%} — 玩家累计销售次数（NPC 购买成功次数）</li>
 *   <li>{@code %woosimmarket_shop_count%} — 玩家拥有的商店数量</li>
 * </ul></p>
 *
 * @author oolongho
 */
public class PlaceholderAPIHook extends PlaceholderExpansion {

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private volatile boolean ready = false;

    public PlaceholderAPIHook(WooSimMarket plugin) {
        this.plugin = plugin;
        this.shopManager = plugin.getShopManager();
    }

    /**
     * 注册占位符扩展。
     *
     * <p>调用方须在实例化前确认 PlaceholderAPI 已安装（{@code extends PlaceholderExpansion}
     * 导致类加载即依赖 PAPI 存在）。</p>
     */
    public void init() {
        register();
        ready = true;
        plugin.getLogger().info(() -> "已挂钩 PlaceholderAPI：占位符注册就绪");
    }

    /**
     * 注销占位符扩展（插件卸载时调用）。
     */
    public void cleanup() {
        if (ready) {
            unregister();
            ready = false;
        }
    }

    @Override
    public String getIdentifier() {
        return "woosimmarket";
    }

    @Override
    public String getAuthor() {
        return "oolongho";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || !ready) {
            return "";
        }
        UUID uuid = player.getUniqueId();
        switch (params.toLowerCase()) {
            case "balance" -> {
                return String.format("%.2f", shopManager.getTotalBalance(uuid));
            }
            case "sales" -> {
                return String.valueOf(shopManager.getSalesCount(uuid));
            }
            case "shop_count" -> {
                return String.valueOf(shopManager.countShopsByOwner(uuid));
            }
            default -> {
                return null;
            }
        }
    }
}
