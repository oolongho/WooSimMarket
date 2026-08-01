package com.oolongho.woosimmarket.listener;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.shop.PricingManager;
import com.oolongho.woosimmarket.shop.ShopNamingManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 聊天事件监听器。
 *
 * <p>捕获定价态/改名态玩家的 {@link AsyncChatEvent}（Paper 推荐的聊天 API），取消事件并
 * 调度到主线程调用对应管理器的 handleInput。调度到主线程是因为成功后
 * 会重开 GUI（定价态）或同步全息文本（改名态），而 Bukkit 的实体操作必须在主线程执行。</p>
 *
 * <p>玩家退出时清理定价态与改名态，防止状态泄漏。</p>
 *
 * @author oolongho
 */
public class ChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final WooSimMarket plugin;
    private final PricingManager pricingManager;
    private final ShopNamingManager shopNamingManager;

    public ChatListener(WooSimMarket plugin, PricingManager pricingManager, ShopNamingManager shopNamingManager) {
        this.plugin = plugin;
        this.pricingManager = pricingManager;
        this.shopNamingManager = shopNamingManager;
    }

    /**
     * 异步聊天事件：定价态/改名态玩家输入转发到主线程处理。
     *
     * <p>使用 {@link EventPriority#LOWEST} 确保在其他聊天处理之前拦截。
     * {@code ignoreCancelled = true} 避免重复处理已被取消的事件。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        boolean pricing = pricingManager.isPricing(player);
        boolean naming = shopNamingManager.isNaming(player);
        if (!pricing && !naming) {
            return;
        }

        event.setCancelled(true);
        String input = PLAIN.serialize(event.message());

        // 调度到主线程：GUI 操作（重开 GUI）/实体操作（更新全息文本）必须在主线程执行
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pricing) {
                pricingManager.handleInput(player, input);
            } else {
                shopNamingManager.handleInput(player, input);
            }
        });
    }

    /**
     * 玩家退出：清理定价态与改名态。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        pricingManager.clear(event.getPlayer().getUniqueId());
        shopNamingManager.clear(event.getPlayer().getUniqueId());
    }
}
