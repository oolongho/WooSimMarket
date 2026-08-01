package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * 商店面板 GUI（InventoryHolder 模式，27 格）。
 *
 * <p>布局（9×3）：
 * <ul>
 *   <li>slot 11：提现按钮（lore 显示当前余额）</li>
 *   <li>slot 13：信息按钮（店主头颅，点击改名）</li>
 *   <li>slot 15：统计按钮</li>
 *   <li>slot 20：购买提示开关（显示当前状态，点击切换）</li>
 *   <li>slot 22：标准价表按钮</li>
 *   <li>slot 24：一键货架切换（显示目标动作，点击切换）</li>
 *   <li>其余：灰色玻璃边框</li>
 * </ul></p>
 *
 * <p>通过 {@link InventoryHolder} 标识 GUI，
 * {@link com.oolongho.woosimmarket.gui.ShopGuiListener} 据此判断事件归属。</p>
 *
 * @author oolongho
 */
public class ShopGui implements InventoryHolder {

    /** 提现按钮槽位（lore 显示余额）。 */
    public static final int SLOT_WITHDRAW = 11;
    /** 信息按钮槽位（店名图标，点击改名）。 */
    public static final int SLOT_INFO = 13;
    /** 统计按钮槽位。 */
    public static final int SLOT_STATS = 15;
    /** 购买提示开关槽位。 */
    public static final int SLOT_NOTIFY_TOGGLE = 20;
    /** 标准价表按钮槽位。 */
    public static final int SLOT_PRICE_TABLE = 22;
    /** 一键货架切换槽位。 */
    public static final int SLOT_SHELF_TOGGLE = 24;
    /** GUI 大小。 */
    public static final int SIZE = 27;

    private final Shop shop;
    private final EconomyManager economyManager;
    private final ShopManager shopManager;
    private final Inventory inventory;

    public ShopGui(Shop shop, EconomyManager economyManager, Messages messages, ShopManager shopManager) {
        this.shop = shop;
        this.economyManager = economyManager;
        this.shopManager = shopManager;
        Component title = messages.get("gui-shop-title");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(messages);
    }

    /**
     * 渲染 GUI 内容：边框 + 全部按钮。
     */
    private void render(Messages messages) {
        ItemStack border = createBorder();
        for (int i = 0; i < SIZE; i++) {
            if (i != SLOT_WITHDRAW && i != SLOT_INFO && i != SLOT_PRICE_TABLE
                    && i != SLOT_STATS && i != SLOT_NOTIFY_TOGGLE && i != SLOT_SHELF_TOGGLE) {
                inventory.setItem(i, border);
            }
        }

        inventory.setItem(SLOT_INFO, createInfoButton(shop, messages));
        inventory.setItem(SLOT_WITHDRAW, createWithdrawButton(shop, economyManager, messages));
        inventory.setItem(SLOT_STATS, createStatsButton(messages));
        inventory.setItem(SLOT_PRICE_TABLE, createPriceTableButton(messages));
        inventory.setItem(SLOT_NOTIFY_TOGGLE, createNotifyButton(shop, messages));
        inventory.setItem(SLOT_SHELF_TOGGLE, createShelfToggleButton(shop, shopManager, messages));
    }

    /**
     * 打开 GUI。
     *
     * @param player 玩家
     */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    /**
     * 刷新动态按钮（提现/通知/货架切换），保留静态按钮与边框。
     *
     * @param messages 消息管理器
     */
    public void refresh(Messages messages) {
        inventory.setItem(SLOT_WITHDRAW, createWithdrawButton(shop, economyManager, messages));
        inventory.setItem(SLOT_NOTIFY_TOGGLE, createNotifyButton(shop, messages));
        inventory.setItem(SLOT_SHELF_TOGGLE, createShelfToggleButton(shop, shopManager, messages));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Shop getShop() {
        return shop;
    }

    // ===== 按钮创建（MiniMessage 渲染） =====

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 信息按钮：店主头颅，显示名为 shop.name，lore 提示点击改名。
     */
    private static ItemStack createInfoButton(Shop shop, Messages messages) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // PLAYER_HEAD 的 meta 为 SkullMeta，setPlayerProfile 后客户端异步拉取纹理（不阻塞主线程）
            if (meta instanceof SkullMeta skullMeta) {
                skullMeta.setPlayerProfile(Bukkit.createProfile(shop.ownerUuid()));
            }
            // 显示名直接使用店名（Component.text 不解析 MiniMessage 标签，安全）
            meta.displayName(Component.text(shop.name()));
            meta.lore(List.of(messages.get("gui-shop-info-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 提现按钮：lore 显示当前余额。
     */
    private static ItemStack createWithdrawButton(Shop shop, EconomyManager economyManager, Messages messages) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-withdraw"));
            String balanceText = economyManager.format(shop.balance());
            meta.lore(List.of(messages.get("gui-shop-balance", "balance", balanceText)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createPriceTableButton(Messages messages) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-price-table"));
            meta.lore(List.of(messages.get("gui-shop-price-table-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createStatsButton(Messages messages) {
        ItemStack item = new ItemStack(Material.MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-stats"));
            meta.lore(List.of(messages.get("gui-shop-stats-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 购买提示开关按钮：显示名反映当前状态（已开启/已关闭），lore 提示点击切换。
     */
    private static ItemStack createNotifyButton(Shop shop, Messages messages) {
        ItemStack item = new ItemStack(Material.BELL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(shop.notifyEnabled()
                    ? messages.get("gui-shop-notify-on")
                    : messages.get("gui-shop-notify-off"));
            meta.lore(List.of(messages.get("gui-shop-notify-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 一键货架切换按钮：显示名为"一键货架切换"，
     * lore 第一行显示目标动作（有禁用则"全部启用"，否则"全部禁用"），
     * 第二行为切换提示。
     */
    private static ItemStack createShelfToggleButton(Shop shop, ShopManager shopManager, Messages messages) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-shelf-toggle"));
            String actionKey = hasAnyDisabledShelf(shop, shopManager)
                    ? "gui-shop-shelf-enable-all"
                    : "gui-shop-shelf-disable-all";
            meta.lore(List.of(
                    messages.get(actionKey),
                    messages.get("gui-shop-shelf-toggle-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 检查商店是否有任一禁用货架（用于判定货架切换按钮的目标动作）。
     */
    private static boolean hasAnyDisabledShelf(Shop shop, ShopManager shopManager) {
        for (Shelf s : shopManager.getShelvesByShop(shop.id())) {
            if (!s.enabled()) {
                return true;
            }
        }
        return false;
    }
}
