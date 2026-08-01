package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.economy.EconomyManager;
import com.oolongho.woosimmarket.model.Shop;
import org.bukkit.Bukkit;
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
 *       信息按钮 → 调度下一 tick 关闭 GUI 并进入改名态（{@link com.oolongho.woosimmarket.shop.ShopNamingManager}）；
 *       提现按钮 → 调用 {@link EconomyManager#withdrawShopBalance} 提现余额到玩家账户，
 *       根据结果发送消息并刷新 GUI 显示新余额；标准价表按钮 → 打开 {@link PriceTableGui}；
 *       统计按钮 → 打开 {@link StatsGui} 交易统计面板（构造时内部异步查询）。</li>
 *   <li>{@link InventoryDragEvent}：涉及任何槽位时取消（防止物品拖入覆盖边框/按钮）。</li>
 * </ul>
 *
 * @author oolongho
 */
public class ShopGuiListener implements Listener {

    private final EconomyManager economyManager;
    private final Messages messages;
    private final WooSimMarket plugin;

    public ShopGuiListener(EconomyManager economyManager, Messages messages, WooSimMarket plugin) {
        this.economyManager = economyManager;
        this.messages = messages;
        this.plugin = plugin;
    }

    /**
     * 点击事件：所有槽位取消点击，信息/提现/标准价表/统计按钮处理对应逻辑。
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

        if (raw == ShopGui.SLOT_INFO) {
            handleInfoClick(gui, player);
        } else if (raw == ShopGui.SLOT_WITHDRAW) {
            handleWithdrawClick(gui, player);
        } else if (raw == ShopGui.SLOT_PRICE_TABLE) {
            handlePriceTableClick(gui, player);
        } else if (raw == ShopGui.SLOT_STATS) {
            handleStatsClick(gui, player);
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
     * 信息按钮：调度到下一 tick 关闭 GUI 并进入改名态（避免在事件处理中触发 InventoryCloseEvent 嵌套）。
     */
    private void handleInfoClick(ShopGui gui, Player player) {
        Shop shop = gui.getShop();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            plugin.getShopNamingManager().startNaming(player, shop);
        });
    }

    /**
     * 提现按钮：调用 EconomyManager 提现商店余额到玩家账户。
     *
     * <p>Vault 不可用或余额为 0 时提示失败/无可提现；
     * 提现成功后刷新 GUI 提现按钮显示新余额（已清零）。</p>
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
     * 统计按钮：打开交易统计面板。
     *
     * <p>{@link StatsGui} 构造时内部已异步查询 purchase_log 并主线程渲染，
     * 此处直接在主线程构造并打开即可，无需额外异步包装。</p>
     */
    private void handleStatsClick(ShopGui gui, Player player) {
        new StatsGui(gui.getShop(), plugin.getPurchaseLogDao(), messages, plugin).open(player);
    }

    /**
     * 标准价表按钮：打开标准价表面板。
     */
    private void handlePriceTableClick(ShopGui gui, Player player) {
        new PriceTableGui(gui.getShop(), economyManager, plugin.getMarketManager(), messages).open(player);
    }
}
