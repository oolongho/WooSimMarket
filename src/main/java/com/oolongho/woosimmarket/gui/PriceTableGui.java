package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.hook.CraftEngineHook;
import com.oolongho.woosimmarket.market.MarketManager;
import com.oolongho.woosimmarket.market.MarketManager.ItemInfo;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.model.Shop;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 标准价表 GUI（InventoryHolder 模式，54 格）。
 *
 * <p>布局（9×6）：
 * <ul>
 *   <li>slot 0：返回按钮（BOOK，回到商店面板）</li>
 *   <li>slot 10-16 / 19-25 / 28-34 / 37-43（28 格）：物品列表分页</li>
 *   <li>slot 45 上一页 / slot 49 页码 / slot 53 下一页</li>
 *   <li>其余槽位：LIME_STAINED_GLASS_PANE 边框</li>
 * </ul></p>
 *
 * <p>物品列表来源 {@link MarketManager#getItemInfos()}，按 itemId 升序保证分页稳定。
 * 翻页边界由 {@link #prevPage()}/{@link #nextPage()} 内部判定，监听器直接调用即可。</p>
 *
 * <p>通过 {@link InventoryHolder} 标识 GUI，{@link PriceTableGuiListener} 据此判断事件归属。</p>
 *
 * @author oolongho
 */
public class PriceTableGui implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int SLOT_BACK = 0;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_PAGE = 49;
    public static final int SLOT_NEXT = 53;
    public static final int ITEMS_PER_PAGE = 28;
    /** 物品槽位列表（rows 2-5 各 7 格，跳过首尾边框列）。 */
    private static final int[] ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Shop shop;
    private final Shelf sourceShelf;
    private final EconomyManager economyManager;
    private final MarketManager marketManager;
    private final Messages messages;
    private final ConfigLoader configLoader;
    private final CraftEngineHook craftEngine;
    private final Inventory inventory;
    private final List<Map.Entry<String, ItemInfo>> entries;
    private final int totalPages;
    private int currentPage;

    /**
     * 从商店面板入口构造（返回时回到 ShopGui）。
     */
    public PriceTableGui(Shop shop, EconomyManager economyManager, MarketManager marketManager,
                         Messages messages, ConfigLoader configLoader, CraftEngineHook craftEngine) {
        this(shop, null, economyManager, marketManager, messages, configLoader, craftEngine);
    }

    /**
     * 从货架面板入口构造（返回时回到 ShelfGui）。
     *
     * @param shop         商店（用于 ShopGui 返回路径，sourceShelf 非空时本字段仅在 shop 仍存在时使用）
     * @param sourceShelf  来源货架（非空表示从 ShelfGui 进入；为空表示从 ShopGui 进入）
     */
    public PriceTableGui(Shop shop, Shelf sourceShelf, EconomyManager economyManager,
                         MarketManager marketManager, Messages messages, ConfigLoader configLoader,
                         CraftEngineHook craftEngine) {
        this.shop = shop;
        this.sourceShelf = sourceShelf;
        this.economyManager = economyManager;
        this.marketManager = marketManager;
        this.messages = messages;
        this.configLoader = configLoader;
        this.craftEngine = craftEngine;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get("gui-price-table-title"));
        // 保序：MarketManager.getItemInfos() 返回 LinkedHashMap，按 items.yml 配置顺序
        this.entries = new ArrayList<>(marketManager.getItemInfos().entrySet());
        this.totalPages = Math.max(1, (entries.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        this.currentPage = 0;
        renderPage();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public Shop getShop() {
        return shop;
    }

    /**
     * 返回来源货架（从 ShelfGui 进入时非空；从 ShopGui 进入时为空）。
     */
    public Shelf getSourceShelf() {
        return sourceShelf;
    }

    /** 翻到上一页（首页时不翻）。 */
    public void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            renderPage();
        }
    }

    /** 翻到下一页（末页时不翻）。 */
    public void nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            renderPage();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ===== 渲染 =====

    private void renderPage() {
        ItemStack border = createBorder();
        // 只填边框槽（非商品槽），商品槽保持 AIR 让玩家看到黑色空槽
        for (int i = 0; i < SIZE; i++) {
            if (!isItemSlot(i)) {
                inventory.setItem(i, border);
            } else {
                inventory.setItem(i, null);
            }
        }

        inventory.setItem(SLOT_BACK, createNavButton(Material.BOOK,
                "gui-price-table-back", "gui-price-table-back-lore"));
        inventory.setItem(SLOT_PREV, createNavButton(Material.ARROW, "gui-price-table-prev", null));
        inventory.setItem(SLOT_PAGE, createPageIndicator());
        inventory.setItem(SLOT_NEXT, createNavButton(Material.ARROW, "gui-price-table-next", null));

        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, entries.size());
        for (int i = start; i < end; i++) {
            Map.Entry<String, ItemInfo> e = entries.get(i);
            inventory.setItem(ITEM_SLOTS[i - start], createItemEntry(e.getKey(), e.getValue()));
        }
    }

    /**
     * 判断槽位是否为商品槽（用于 renderPage 跳过填充玻璃板）。
     */
    private static boolean isItemSlot(int slot) {
        for (int s : ITEM_SLOTS) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    private ItemStack createItemEntry(String itemId, ItemInfo info) {
        ItemStack item = craftEngine.createItemStack(itemId);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(craftEngine.displayName(itemId));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get("gui-price-table-item-lore",
                    "price", economyManager.format(info.standardPrice())));
            // drift.enabled=true 时追加漂移后价行 + 箭头
            if (configLoader.isDriftEnabled()) {
                double drift = marketManager.getPriceDrift(itemId);
                double effStd = info.standardPrice() * drift;
                String arrow = drift > 1.005 ? "↑" : (drift < 0.995 ? "↓" : "−");
                lore.add(messages.get("gui-price-table-item-lore-drift",
                        "drifted", economyManager.format(effStd),
                        "arrow", arrow));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPageIndicator() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-price-table-page",
                    "current", String.valueOf(currentPage + 1),
                    "total", String.valueOf(totalPages)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createNavButton(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get(nameKey));
            if (loreKey != null) {
                meta.lore(List.of(messages.get(loreKey)));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
