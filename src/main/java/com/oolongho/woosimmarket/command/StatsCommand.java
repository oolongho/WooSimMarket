package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.gui.StatsGui;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm stats 子命令：打开玩家第一家商店的统计面板。
 *
 * <p>仅玩家可执行（需要拥有商店）。权限：woosimmarket.use。
 * 统计面板的异步查询与渲染由 {@link StatsGui} 内部处理，
 * 本命令仅负责权限/玩家校验、定位商店并打开面板。</p>
 *
 * @author oolongho
 */
public class StatsCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;
    private final ShopManager shopManager;

    public StatsCommand(WooSimMarket plugin, Messages messages) {
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

        List<Shop> shops = shopManager.getAllShops().stream()
                .filter(s -> s.ownerUuid().equals(player.getUniqueId()))
                .toList();

        if (shops.isEmpty()) {
            messages.send(player, "shop-not-found");
            return true;
        }

        Shop shop = shops.get(0);
        new StatsGui(shop, plugin.getPurchaseLogDao(), messages, plugin).open(player);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
