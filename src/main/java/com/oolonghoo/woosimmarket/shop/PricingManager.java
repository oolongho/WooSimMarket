package com.oolonghoo.woosimmarket.shop;

import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.gui.ShelfGui;
import com.oolonghoo.woosimmarket.model.Shelf;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定价态管理器。
 *
 * <p>玩家点击设价按钮后进入定价态，后续聊天输入被捕获为价格。
 * 使用 {@link ConcurrentHashMap} 维护待定价映射，玩家退出时清理。</p>
 *
 * <p>聊天事件由 {@link com.oolonghoo.woosimmarket.listener.ChatListener} 监听并转发到此。
 * 该监听器在异步聊天事件中取消事件，并调度到主线程调用 {@link #handleInput}，
 * 因此本类所有方法均在主线程执行，无需额外同步。</p>
 *
 * @author oolongho
 */
public class PricingManager {

    private final ShopManager shopManager;
    private final Messages messages;
    private final Map<UUID, Shelf> pending = new ConcurrentHashMap<>();

    public PricingManager(ShopManager shopManager, Messages messages) {
        this.shopManager = shopManager;
        this.messages = messages;
    }

    /**
     * 让玩家进入定价态。
     *
     * @param player 玩家
     * @param shelf  待定价的货架
     */
    public void startPricing(Player player, Shelf shelf) {
        pending.put(player.getUniqueId(), shelf);
        messages.send(player, "price-input-prompt");
    }

    /**
     * 处理玩家输入。
     *
     * <p>解析成功写入 price 并落库后重开 GUI；失败提示重输（保持定价态）；
     * 输入 cancel/取消 则退出定价态。</p>
     *
     * @param player 玩家
     * @param input  聊天输入
     */
    public void handleInput(Player player, String input) {
        Shelf shelf = pending.get(player.getUniqueId());
        if (shelf == null) {
            return;
        }

        String trimmed = input.trim();

        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("取消")) {
            pending.remove(player.getUniqueId());
            messages.send(player, "price-input-cancelled");
            return;
        }

        try {
            double price = Double.parseDouble(trimmed);
            if (!Double.isFinite(price) || price <= 0) {
                messages.send(player, "price-input-invalid");
                return;
            }
            // 货架已被删除（方块破坏等），取消定价态
            if (shopManager.getShelf(shelf.id()) == null) {
                pending.remove(player.getUniqueId());
                messages.send(player, "shelf-not-found");
                return;
            }
            shelf.price(price);
            shopManager.saveShelf(shelf);
            pending.remove(player.getUniqueId());
            messages.send(player, "price-set", "price", String.format("%.2f", price));
            new ShelfGui(shelf, messages).open(player);
        } catch (NumberFormatException e) {
            messages.send(player, "price-input-invalid");
        }
    }

    /**
     * 取消定价态。
     *
     * @param player 玩家
     */
    public void cancelPricing(Player player) {
        if (pending.remove(player.getUniqueId()) != null) {
            messages.send(player, "price-input-cancelled");
        }
    }

    /**
     * 玩家是否在定价态。
     *
     * @param player 玩家
     * @return 在定价态返回 true
     */
    public boolean isPricing(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /**
     * 玩家退出时清理定价态（不发送消息）。
     *
     * @param playerUuid 玩家 UUID
     */
    public void clear(UUID playerUuid) {
        pending.remove(playerUuid);
    }
}
