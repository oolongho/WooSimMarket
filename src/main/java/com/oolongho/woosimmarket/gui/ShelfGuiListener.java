package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.shop.PricingManager;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
 *   <li>{@link InventoryClickEvent}：9 个商品槽允许自由放入/取出，但放入物品须为已绑定种类
 *       （未绑定时任意种类绑定首个）；shift-click 从背包移入同样校验种类；
 *       number-key/offhand-swap 因种类不可控而取消；按钮槽处理对应逻辑；边框槽取消。</li>
 *   <li>{@link InventoryDragEvent}：拖拽仅允许涉及 9 个商品槽，且拖入物品须为绑定种类（未绑定时任意）。</li>
 *   <li>{@link InventoryCloseEvent}：汇总 9 格 stock 与模板，同步 shelf 并落库；9 格全空则解绑。</li>
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
     * 点击事件：商品槽校验种类后放行，按钮槽处理，边框取消。
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

        Material bound = gui.liveBoundMaterial();

        if (raw >= ShelfGui.SIZE) {
            // 玩家自身背包：shift-click 移入 GUI 时校验种类
            if (event.isShiftClick()) {
                ItemStack src = event.getCurrentItem();
                if (src != null && !src.getType().isAir() && bound != null && src.getType() != bound) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        // GUI 内商品槽
        if (ShelfGui.isItemSlot(raw)) {
            ClickType click = event.getClick();
            // number-key / offhand-swap 种类不可控，安全取消
            if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
                event.setCancelled(true);
                return;
            }
            // 非_shift 点击用 cursor 放入，校验种类（shift-click 商品槽为取出，不校验）
            if (!event.isShiftClick()) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir() && bound != null && cursor.getType() != bound) {
                    event.setCancelled(true);
                    return;
                }
            }
            // 取出 / 同种类放入：放行
            return;
        }

        // 非商品槽（边框/按钮）：取消点击
        event.setCancelled(true);

        if (raw == ShelfGui.SLOT_PRICE) {
            handlePriceClick(gui, (Player) event.getWhoClicked());
        } else if (raw == ShelfGui.SLOT_TOGGLE) {
            handleToggleClick(gui);
        }
    }

    /**
     * 拖拽事件：仅允许涉及 9 个商品槽，且拖入物品须为绑定种类（未绑定时任意）。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShelfGui gui)) {
            return;
        }
        // 涉及任何非商品槽的 GUI 内槽位 → 取消
        for (int slot : event.getRawSlots()) {
            if (slot < ShelfGui.SIZE && !ShelfGui.isItemSlot(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        // 拖入物品须为绑定种类（未绑定时任意）
        Material bound = gui.liveBoundMaterial();
        if (bound != null) {
            ItemStack oldCursor = event.getOldCursor();
            if (oldCursor != null && !oldCursor.getType().isAir() && oldCursor.getType() != bound) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * 关闭事件：汇总 9 格 stock 与模板，同步 shelf 并落库；9 格全空则解绑。
     *
     * <p>若货架已被删除（如方块被破坏），将 9 格物品归还给玩家，
     * 背包满时掉落在玩家脚下，避免物品静默丢失。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShelfGui gui)) {
            return;
        }

        Shelf shelf = gui.getShelf();
        int stock = gui.collectStock();
        ItemStack template = gui.collectTemplate();

        // 货架已被删除（方块破坏等），归还 9 格物品给玩家
        if (shopManager.getShelf(shelf.id()) == null) {
            if (event.getPlayer() instanceof Player player) {
                for (int slot : ShelfGui.ITEM_SLOTS) {
                    ItemStack it = gui.getInventory().getItem(slot);
                    if (it != null && !it.getType().isAir()) {
                        var leftover = player.getInventory().addItem(it);
                        leftover.values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                    }
                }
            }
            return;
        }

        if (stock == 0 || template == null) {
            // 9 格全空 → 解绑
            shelf.itemStack(null);
            shelf.stock(0);
        } else {
            shelf.itemStack(template);
            shelf.stock(stock);
        }
        shopManager.saveShelf(shelf);
        shelfDisplayManager.refreshShelf(shelf);
    }

    // ===== 内部处理 =====

    /**
     * 设价按钮：调度到下一 tick 关闭 GUI 并进入定价态（避免在事件处理中触发 InventoryCloseEvent 嵌套）。
     */
    private void handlePriceClick(ShelfGui gui, Player player) {
        Shelf shelf = gui.getShelf();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            pricingManager.startPricing(player, shelf);
        });
    }

    /**
     * 启用/禁用按钮：切换状态、落库、刷新 GUI、刷新展示。
     */
    private void handleToggleClick(ShelfGui gui) {
        Shelf shelf = gui.getShelf();
        shelf.enabled(!shelf.enabled());
        shopManager.saveShelf(shelf);
        gui.refresh(messages);
        shelfDisplayManager.refreshShelf(shelf);
    }
}
