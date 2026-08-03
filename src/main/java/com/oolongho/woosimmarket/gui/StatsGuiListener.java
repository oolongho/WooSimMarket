package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 商店统计面板 GUI 交互监听器。
 *
 * <p>通过 {@link org.bukkit.inventory.InventoryHolder} 类型判断事件归属（{@link StatsGui}）。
 * 处理两类事件：</p>
 * <ul>
 *   <li>{@link InventoryClickEvent}：所有槽位均取消点击（纯信息面板，不允许放入/取出物品）；
 *       返回按钮 → 调度到下一 tick 关闭并打开 {@link ShopGui}（避免在事件处理中
 *       触发 {@link org.bukkit.event.inventory.InventoryCloseEvent} 产生嵌套事件）；
 *       刷新按钮 → 调用 {@link StatsGui#refresh()} 重新异步查询并渲染。</li>
 *   <li>{@link InventoryDragEvent}：涉及任何槽位时取消（防止物品拖入覆盖边框/按钮）。</li>
 * </ul>
 *
 * @author oolongho
 */
public class StatsGuiListener implements Listener {

    private final WooSimMarket plugin;
    private final EconomyManager economyManager;
    private final Messages messages;

    public StatsGuiListener(WooSimMarket plugin, EconomyManager economyManager, Messages messages) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        this.messages = messages;
    }

    /**
     * 点击事件：所有槽位取消点击，返回/刷新按钮处理对应逻辑。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StatsGui gui)) {
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0) {
            // 点击 GUI 外部（丢弃物品等），放行
            return;
        }
        if (raw >= StatsGui.SIZE) {
            // 玩家自身背包：shift-click 时取消（防止物品移入边框/按钮槽被挡但仍尝试）
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        // GUI 内槽位：一律取消点击（纯信息面板，不允许放入/取出物品）
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (raw == StatsGui.SLOT_BACK) {
            handleBackClick(gui, player);
        } else if (raw == StatsGui.SLOT_REFRESH) {
            gui.refresh();
        }
    }

    /**
     * 拖拽事件：涉及任何 GUI 内槽位时取消。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof StatsGui)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < StatsGui.SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ===== 内部处理 =====

    /**
     * 返回按钮：调度到下一 tick 关闭当前 GUI 并打开 ShopGui。
     *
     * <p>不在事件处理中直接关闭，避免 InventoryCloseEvent 在 InventoryClickEvent
     * 处理期间触发，产生嵌套事件导致状态不一致（与 ShelfGuiListener 设价按钮同模式）。</p>
     */
    private void handleBackClick(StatsGui gui, Player player) {
        Shop shop = gui.getShop();
        SchedulerUtil.runTask(() -> {
            player.closeInventory();
            new ShopGui(shop, economyManager, messages, plugin.getShopManager()).open(player);
        });
    }
}
