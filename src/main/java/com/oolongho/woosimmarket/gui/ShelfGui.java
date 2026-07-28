package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shelf;
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
 * 货架管理 GUI（InventoryHolder 模式）。
 *
 * <p>27 格箱子布局：
 * <ul>
 *   <li>slot 13：商品槽（玩家放入/取出物品，amount = stock）</li>
 *   <li>slot 11：设价按钮（点击 → 关闭 GUI → 聊天定价）</li>
 *   <li>slot 15：启用/禁用按钮（点击切换状态）</li>
 *   <li>其余：灰色玻璃边框（不可交互）</li>
 * </ul></p>
 *
 * <p>通过 {@link InventoryHolder} 标识 GUI，
 * {@link com.oolongho.woosimmarket.gui.ShelfGuiListener} 据此判断事件归属。</p>
 *
 * @author oolongho
 */
public class ShelfGui implements InventoryHolder {

    /** 商品槽位。 */
    public static final int SLOT_ITEM = 13;
    /** 设价按钮槽位。 */
    public static final int SLOT_PRICE = 11;
    /** 启用/禁用按钮槽位。 */
    public static final int SLOT_TOGGLE = 15;
    /** GUI 大小。 */
    public static final int SIZE = 27;

    private final Shelf shelf;
    private final Inventory inventory;

    public ShelfGui(Shelf shelf, Messages messages) {
        this.shelf = shelf;
        Component title = messages.get("gui-shelf-title");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(messages);
    }

    /**
     * 渲染 GUI 内容：边框 + 商品 + 按钮。
     */
    private void render(Messages messages) {
        ItemStack border = createBorder();
        for (int i = 0; i < SIZE; i++) {
            if (i != SLOT_ITEM && i != SLOT_PRICE && i != SLOT_TOGGLE) {
                inventory.setItem(i, border);
            }
        }

        // 商品槽：显示 itemStack 的克隆，amount = min(stock, maxStackSize)
        if (shelf.itemStack() != null && !shelf.itemStack().getType().isAir() && shelf.stock() > 0) {
            ItemStack display = shelf.itemStack().clone();
            display.setAmount(Math.min(shelf.stock(), display.getMaxStackSize()));
            inventory.setItem(SLOT_ITEM, display);
        }

        inventory.setItem(SLOT_PRICE, createPriceButton(shelf.price(), messages));
        inventory.setItem(SLOT_TOGGLE, createToggleButton(shelf.enabled(), messages));
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
     * 刷新按钮显示（切换启用/禁用后更新按钮材质与文案）。
     *
     * <p>不清空商品槽——玩家可能已放入物品但尚未关闭 GUI（itemStack 仅在关闭时持久化），
     * 清空会导致物品丢失。</p>
     *
     * @param messages 消息管理器
     */
    public void refresh(Messages messages) {
        inventory.setItem(SLOT_PRICE, createPriceButton(shelf.price(), messages));
        inventory.setItem(SLOT_TOGGLE, createToggleButton(shelf.enabled(), messages));
    }

    /**
     * 获取商品槽的当前物品（关闭 GUI 时调用，用于持久化）。
     *
     * @return 商品槽物品；空槽返回 null
     */
    public ItemStack getItemSlotContent() {
        ItemStack item = inventory.getItem(SLOT_ITEM);
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Shelf getShelf() {
        return shelf;
    }

    /**
     * 判断槽位是否为商品槽。
     */
    public static boolean isItemSlot(int slot) {
        return slot == SLOT_ITEM;
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

    private static ItemStack createPriceButton(double price, Messages messages) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shelf-set-price"));
            meta.lore(List.of(messages.get("gui-shelf-current-price", "price", String.format("%.2f", price))));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createToggleButton(boolean enabled, Messages messages) {
        ItemStack item = new ItemStack(enabled ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get(enabled ? "gui-shelf-enabled" : "gui-shelf-disabled"));
            meta.lore(List.of(messages.get("gui-shelf-toggle-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }
}
