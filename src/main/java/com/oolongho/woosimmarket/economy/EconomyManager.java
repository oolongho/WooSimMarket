package com.oolongho.woosimmarket.economy;

import com.oolongho.woosimmarket.hook.VaultHook;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import org.bukkit.OfflinePlayer;

/**
 * 经济管理器。
 *
 * <p>作为业务层对 Vault 经济服务的访问入口，封装 {@link VaultHook} 的原始操作，
 * 并提供商店余额提现事务（扣减 Shop.balance → 存入玩家账户，失败回滚）。</p>
 *
 * <p>事务安全：先扣减 Shop.balance 并落库，再存入玩家账户；若 Vault 存款失败，
 * 将扣减金额加回 Shop.balance 并落库，恢复原始状态。事务窗口在毫秒级，
 * 对 MVP 场景足够；更严格的原子性需要数据库事务，留待后续。</p>
 *
 * @author oolongho
 */
public class EconomyManager {

    private final VaultHook vaultHook;
    private final ShopManager shopManager;

    public EconomyManager(VaultHook vaultHook, ShopManager shopManager) {
        this.vaultHook = vaultHook;
        this.shopManager = shopManager;
    }

    /**
     * Vault 经济服务是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isReady() {
        return vaultHook.isReady();
    }

    /**
     * 向玩家账户存款。
     *
     * @param player 离线玩家
     * @param amount 金额
     * @return 成功返回 true；Vault 不可用或存款失败返回 false
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        return vaultHook.deposit(player, amount);
    }

    /**
     * 检查玩家是否有经济账户。
     *
     * @param player 离线玩家
     * @return 有账户返回 true；Vault 不可用返回 false
     */
    public boolean hasAccount(OfflinePlayer player) {
        return vaultHook.hasAccount(player);
    }

    /**
     * 格式化金额为带货币符号的字符串。
     *
     * @param amount 金额
     * @return 格式化字符串；Vault 不可用返回原始数字
     */
    public String format(double amount) {
        return vaultHook.format(amount);
    }

    /**
     * 提现商店余额到玩家账户。
     *
     * <p>事务流程：
     * <ol>
     *   <li>读取 Shop.balance，若 ≤ 0 返回 0</li>
     *   <li>扣减 Shop.balance 并落库</li>
     *   <li>Vault 存款到玩家账户</li>
     *   <li>若存款失败，回滚 Shop.balance 并落库，返回 0</li>
     * </ol></p>
     *
     * @param shop  商店
     * @param owner 拥有者
     * @return 实际提现金额；无可提现或失败返回 0
     */
    public double withdrawShopBalance(Shop shop, OfflinePlayer owner) {
        if (!vaultHook.isReady()) {
            return 0;
        }
        double balance = shop.balance();
        if (balance <= 0) {
            return 0;
        }

        double actual = shopManager.withdraw(shop, balance);
        if (actual <= 0) {
            return 0;
        }

        if (!vaultHook.deposit(owner, actual)) {
            // 回滚
            shopManager.addBalance(shop, actual);
            return 0;
        }

        return actual;
    }
}
