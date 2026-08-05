package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm range 子命令：可视化发送者脚下所属商店的绑定范围。
 *
 * <p>仅玩家可执行。在玩家所在位置附近搜索最近的商店，
 * 若属于该玩家则调用 {@link com.oolongho.woosimmarket.visualize.ShopRangeVisualizer}
 * 显示范围粒子，并发送提示消息（含持续秒数占位符）。</p>
 *
 * @author oolongho
 */
public class RangeCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;
    private final ShopManager shopManager;

    public RangeCommand(WooSimMarket plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.shopManager = plugin.getShopManager();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woosimmarket.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            messages.send(sender, "player-only");
            return true;
        }

        Location loc = player.getLocation();
        int searchRadius = plugin.getConfigLoader().getShopBindRadius() + 5;
        Shop shop = shopManager.findNearestShop(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                searchRadius);

        if (shop == null || !shop.ownerUuid().equals(player.getUniqueId())) {
            messages.send(sender, "range-not-found");
            return true;
        }

        plugin.getShopRangeVisualizer().showRange(player, shop);
        messages.send(player, "range-showing",
                "seconds", String.valueOf(plugin.getConfigLoader().getRangeDurationSeconds()));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
