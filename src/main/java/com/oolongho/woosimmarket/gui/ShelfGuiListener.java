package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.shop.PricingManager;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 货架 GUI 交互监听器。
 *
 * <p>通过 {@link org.bukkit.inventory.InventoryHolder} 类型判断事件归属（{@link ShelfGui}）。
 * 处理三类事件：</p>
 * <ul>
 *   <li>{@link InventoryClickEvent}：商品槽允许自由放入/取出；按钮槽处理对应逻辑；
 *       边框槽全部取消。设价按钮点击后调度到下一 tick 关闭 GUI 并进入定价态，
 *       避免在事件处理中触发 {@link InventoryCloseEvent} 产生嵌套事件。</li>
 *   <li>{@link InventoryDragEvent}：拖拽涉及非商品槽时取消。</li>
 *   <li>{@link InventoryCloseEvent}：读取商品槽内容，同步 shelf 的 itemStack/stock 并落库。</li>
 * </ul>
 *
 * @author oolongho
 */
public class ShelfGuiListener implements Listener {

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final PricingManager pricingManager;
    private final Messages messages;
    private final ShelfDisplayManager shelfDisplayManager;

    public ShelfGuiListener(WooSimMarket plugin, ShopManager shopManager,
                            PricingManager pricingManager, Messages messages,
                            ShelfDisplayManager shelfDisplayManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.pricingManager = pricingManager;
        this.messages = messages;
        this.shelfDisplayManager = shelfDisplayManager;
    }

    /**
     * 点击事件：商品槽放行，按钮槽处理，边框取消。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShelfGui gui)) {
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0) {
            // 点击 GUI 外部（丢弃物品等），放行
            return;
        }
        if (raw >= ShelfGui.SIZE) {
            // 玩家自身背包：shift-click 时取消（防止物品移入边框/按钮槽被挡但仍尝试）
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        // GUI 内槽位
        if (ShelfGui.isItemSlot(raw)) {
            // 商品槽：允许自由放入/取出
            return;
        }

        // 非商品槽一律取消点击
        event.setCancelled(true);

        if (raw == ShelfGui.SLOT_PRICE) {
            handlePriceClick(gui, (Player) event.getWhoClicked());
        } else if (raw == ShelfGui.SLOT_TOGGLE) {
            handleToggleClick(gui);
        }
    }

    /**
     * 拖拽事件：涉及任何非商品槽时取消。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShelfGui)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < ShelfGui.SIZE && !ShelfGui.isItemSlot(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * 关闭事件：持久化商品槽内容到 Shelf 并落库。
     *
     * <p>若货架已被删除（如方块被破坏），将商品槽物品归还给玩家，
     * 背包满时掉落在玩家脚下，避免物品静默丢失。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShelfGui gui)) {
            return;
        }

        Shelf shelf = gui.getShelf();
        ItemStack item = gui.getItemSlotContent();

        // 货架已被删除（方块破坏等），归还物品给玩家
        if (shopManager.getShelf(shelf.id()) == null) {
            if (item != null && event.getPlayer() instanceof Player player) {
                var leftover = player.getInventory().addItem(item);
                leftover.values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            }
            return;
        }

        if (item == null) {
            shelf.itemStack(null);
            shelf.stock(0);
        } else {
            ItemStack template = item.clone();
            template.setAmount(1);
            shelf.itemStack(template);
            shelf.stock(item.getAmount());
        }
        shopManager.saveShelf(shelf);
        shelfDisplayManager.refreshShelf(shelf);
    }

    // ===== 内部处理 =====

    /**
     * 设价按钮：调度到下一 tick 关闭 GUI 并进入定价态。
     *
     * <p>不在事件处理中直接关闭，避免 InventoryCloseEvent 在 InventoryClickEvent
     * 处理期间触发，产生嵌套事件导致状态不一致。</p>
     */
    private void handlePriceClick(ShelfGui gui, Player player) {
        Shelf shelf = gui.getShelf();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            pricingManager.startPricing(player, shelf);
        });
    }

    /**
     * 启用/禁用按钮：切换状态、落库、刷新 GUI。
     */
    private void handleToggleClick(ShelfGui gui) {
        Shelf shelf = gui.getShelf();
        shelf.enabled(!shelf.enabled());
        shopManager.saveShelf(shelf);
        gui.refresh(messages);
        shelfDisplayManager.refreshShelf(shelf);
    }
}
