package com.oolongho.woosimmarket.shop;

import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.visualize.ShopDisplayManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店改名态管理器。
 *
 * <p>玩家在商店面板点击信息按钮后进入改名态，后续聊天输入被捕获为新店名。
 * 镜像 {@link PricingManager} 模式：{@link ConcurrentHashMap} 维护待改名映射，
 * 玩家退出时清理。</p>
 *
 * <p>聊天事件由 {@link com.oolongho.woosimmarket.listener.ChatListener} 监听并转发到此。
 * 该监听器在异步聊天事件中取消事件，并调度到主线程调用 {@link #handleInput}，
 * 因此本类所有方法均在主线程执行，无需额外同步。</p>
 *
 * @author oolongho
 */
public class ShopNamingManager {

    /** 店名最大长度（trim 后）。 */
    private static final int MAX_NAME_LENGTH = 20;

    private final ShopManager shopManager;
    private final ShopDisplayManager shopDisplayManager;
    private final Messages messages;
    private final Map<UUID, Shop> pending = new ConcurrentHashMap<>();

    public ShopNamingManager(ShopManager shopManager, ShopDisplayManager shopDisplayManager, Messages messages) {
        this.shopManager = shopManager;
        this.shopDisplayManager = shopDisplayManager;
        this.messages = messages;
    }

    /**
     * 让玩家进入改名态。
     *
     * @param player 玩家
     * @param shop   待改名的商店
     */
    public void startNaming(Player player, Shop shop) {
        pending.put(player.getUniqueId(), shop);
        messages.send(player, "shop-rename-prompt");
    }

    /**
     * 处理玩家输入。
     *
     * <p>校验通过后调用 {@link ShopManager#renameShop} 落库并同步全息文本；
     * 失败提示重输（保持改名态）；输入 cancel/取消 则退出改名态。</p>
     *
     * @param player 玩家
     * @param input  聊天输入
     */
    public void handleInput(Player player, String input) {
        Shop shop = pending.get(player.getUniqueId());
        if (shop == null) {
            return;
        }

        String trimmed = input.trim();

        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("取消")) {
            pending.remove(player.getUniqueId());
            messages.send(player, "shop-rename-cancelled");
            return;
        }

        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            messages.send(player, "shop-rename-invalid");
            return;
        }

        // 商店已被删除（方块破坏等），取消改名态
        if (shopManager.getShop(shop.id()) == null) {
            pending.remove(player.getUniqueId());
            messages.send(player, "shop-not-found");
            return;
        }

        shopManager.renameShop(shop, trimmed);
        shopDisplayManager.updateShopName(shop);
        pending.remove(player.getUniqueId());
        messages.send(player, "shop-rename-success", "name", trimmed);
    }

    /**
     * 玩家是否在改名态。
     *
     * @param player 玩家
     * @return 在改名态返回 true
     */
    public boolean isNaming(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /**
     * 玩家退出时清理改名态（不发送消息）。
     *
     * @param playerUuid 玩家 UUID
     */
    public void clear(UUID playerUuid) {
        pending.remove(playerUuid);
    }
}
