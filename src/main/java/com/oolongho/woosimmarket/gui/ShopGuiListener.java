package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.model.Shop;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 商店面板 GUI 交互监听器。
 *
 * <p>通过 {@link org.bukkit.inventory.InventoryHolder} 类型判断事件归属（{@link ShopGui}）。
 * 处理两类事件：</p>
 * <ul>
 *   <li>{@link InventoryClickEvent}：所有槽位均取消点击（纯信息面板，不允许放入/取出物品）；
 *       提现按钮 → 调用 {@link EconomyManager#withdrawShopBalance} 提现余额到玩家账户，
 *       根据结果发送消息并刷新 GUI 显示新余额；刷新按钮 → 重新读取余额并刷新信息按钮。</li>
 *   <li>{@link InventoryDragEvent}：涉及任何槽位时取消（防止物品拖入覆盖边框/按钮）。</li>
 * </ul>
 *
 * @author oolongho
 */
public class ShopGuiListener implements Listener {

    private final EconomyManager economyManager;
    private final Messages messages;

    public ShopGuiListener(EconomyManager economyManager, Messages messages) {
        this.economyManager = economyManager;
        this.messages = messages;
    }

    /**
     * 点击事件：所有槽位取消点击，提现/刷新按钮处理对应逻辑。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopGui gui)) {
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0) {
            // 点击 GUI 外部（丢弃物品等），放行
            return;
        }
        if (raw >= ShopGui.SIZE) {
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

        if (raw == ShopGui.SLOT_WITHDRAW) {
            handleWithdrawClick(gui, player);
        } else if (raw == ShopGui.SLOT_REFRESH) {
            handleRefreshClick(gui);
        }
    }

    /**
     * 拖拽事件：涉及任何 GUI 内槽位时取消。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopGui)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < ShopGui.SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ===== 内部处理 =====

    /**
     * 提现按钮：调用 EconomyManager 提现商店余额到玩家账户。
     *
     * <p>Vault 不可用或余额为 0 时提示失败/无可提现；
     * 提现成功后刷新 GUI 信息按钮显示新余额（已清零）。</p>
     */
    private void handleWithdrawClick(ShopGui gui, Player player) {
        Shop shop = gui.getShop();

        // Vault 不可用时直接提示失败
        if (!economyManager.isReady()) {
            messages.send(player, "withdraw-failed");
            return;
        }

        double balance = shop.balance();
        if (balance <= 0) {
            messages.send(player, "withdraw-no-balance");
            return;
        }

        double actual = economyManager.withdrawShopBalance(shop, player);
        if (actual <= 0) {
            messages.send(player, "withdraw-failed");
            return;
        }

        messages.send(player, "withdraw-success", "amount", economyManager.format(actual));
        gui.refresh(messages);
    }

    /**
     * 刷新按钮：重新读取余额并刷新信息按钮显示。
     */
    private void handleRefreshClick(ShopGui gui) {
        gui.refresh(messages);
    }
}
