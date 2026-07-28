package com.oolonghoo.woosimmarket.hook;

import com.oolonghoo.woosimmarket.WooSimMarket;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济钩子。
 *
 * <p>检测 Vault 是否在线并获取 {@link Economy} 服务。所有方法在 Vault 不可用时安全降级：
 * {@link #isReady()} 返回 false，{@link #deposit} 返回 false，{@link #format} 返回原始数字字符串。</p>
 *
 * @author oolongho
 */
public class VaultHook {

    private final WooSimMarket plugin;
    private volatile Economy economy;
    private volatile boolean ready = false;

    public VaultHook(WooSimMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * 检测 Vault 并获取 Economy 服务。
     *
     * @return 成功获取返回 true；Vault 不存在或获取失败返回 false
     */
    public boolean init() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning(() -> "未找到 Vault，经济功能不可用（提现将失败）");
            ready = false;
            return false;
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning(() -> "Vault 已安装但未找到 Economy 服务提供者");
            ready = false;
            return false;
        }

        economy = rsp.getProvider();
        ready = economy != null;
        if (ready) {
            plugin.getLogger().info(() -> "已挂钩 Vault 经济服务");
        }
        return ready;
    }

    /**
     * Vault 经济服务是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 获取 Economy 实例。
     *
     * @return Economy；不可用返回 null
     */
    public Economy getEconomy() {
        return economy;
    }

    /**
     * 向玩家账户存款。
     *
     * @param player 离线玩家
     * @param amount 金额
     * @return 成功返回 true；Vault 不可用或存款失败返回 false
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!ready || player == null || amount <= 0 || !Double.isFinite(amount)) {
            return false;
        }
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    /**
     * 检查玩家是否有经济账户。
     *
     * @param player 离线玩家
     * @return 有账户返回 true；Vault 不可用返回 false
     */
    public boolean hasAccount(OfflinePlayer player) {
        if (!ready || player == null) {
            return false;
        }
        return economy.hasAccount(player);
    }

    /**
     * 格式化金额为带货币符号的字符串。
     *
     * @param amount 金额
     * @return 格式化字符串；Vault 不可用返回原始数字
     */
    public String format(double amount) {
        if (!ready) {
            return String.format("%.2f", amount);
        }
        return economy.format(amount);
    }
}
