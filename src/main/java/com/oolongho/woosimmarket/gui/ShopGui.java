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

import java.util.List;

/**
 * 商店面板 GUI（InventoryHolder 模式）。
 *
 * <p>27 格箱子布局：
 * <ul>
 *   <li>slot 13：信息按钮（显示当前余额与货架启用数量）</li>
 *   <li>slot 11：提现按钮（点击 → 调用 EconomyManager.withdrawShopBalance → 刷新 GUI）</li>
 *   <li>slot 15：刷新按钮（点击重新读取余额并刷新显示）</li>
 *   <li>其余：灰色玻璃边框（不可交互）</li>
 * </ul></p>
 *
 * <p>通过 {@link InventoryHolder} 标识 GUI，
 * {@link com.oolongho.woosimmarket.gui.ShopGuiListener} 据此判断事件归属。</p>
 *
 * @author oolongho
 */
public class ShopGui implements InventoryHolder {

    /** 提现按钮槽位。 */
    public static final int SLOT_WITHDRAW = 11;
    /** 信息按钮槽位。 */
    public static final int SLOT_INFO = 13;
    /** 刷新按钮槽位。 */
    public static final int SLOT_REFRESH = 15;
    /** GUI 大小。 */
    public static final int SIZE = 27;

    private final Shop shop;
    private final EconomyManager economyManager;
    private final ShopManager shopManager;
    private final Inventory inventory;

    public ShopGui(Shop shop, EconomyManager economyManager, ShopManager shopManager, Messages messages) {
        this.shop = shop;
        this.economyManager = economyManager;
        this.shopManager = shopManager;
        Component title = messages.get("gui-shop-title");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(messages);
    }

    /**
     * 渲染 GUI 内容：边框 + 信息 + 提现 + 刷新按钮。
     */
    private void render(Messages messages) {
        ItemStack border = createBorder();
        for (int i = 0; i < SIZE; i++) {
            if (i != SLOT_WITHDRAW && i != SLOT_INFO && i != SLOT_REFRESH) {
                inventory.setItem(i, border);
            }
        }

        inventory.setItem(SLOT_INFO, createInfoButton(shop, economyManager, shopManager, messages));
        inventory.setItem(SLOT_WITHDRAW, createWithdrawButton(messages));
        inventory.setItem(SLOT_REFRESH, createRefreshButton(messages));
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
     * 刷新信息按钮显示（提现后或手动刷新后调用）。
     *
     * @param messages 消息管理器
     */
    public void refresh(Messages messages) {
        inventory.setItem(SLOT_INFO, createInfoButton(shop, economyManager, shopManager, messages));
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

    private static ItemStack createInfoButton(Shop shop, EconomyManager economyManager, ShopManager shopManager, Messages messages) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-info"));
            String balanceText = economyManager.format(shop.balance());
            // 统计货架启用数量
            List<Shelf> shelves = shopManager.getShelvesByShop(shop.id());
            int enabled = (int) shelves.stream().filter(Shelf::enabled).count();
            int total = shelves.size();
            meta.lore(List.of(
                    messages.get("gui-shop-balance", "balance", balanceText),
                    messages.get("gui-shop-shelves", "enabled", String.valueOf(enabled), "total", String.valueOf(total))
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createWithdrawButton(Messages messages) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-withdraw"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createRefreshButton(Messages messages) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shop-refresh"));
            item.setItemMeta(meta);
        }
        return item;
    }
}
