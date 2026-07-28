package com.oolonghoo.woosimmarket.command;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.model.Shop;
import com.oolonghoo.woosimmarket.shop.ShopManager;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm info 子命令：查看发送者的商店信息（ID、余额）。
 *
 * <p>仅玩家可执行（需要拥有商店）。权限：woosimmarket.use。</p>
 *
 * @author oolongho
 */
public class InfoCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;
    private final ShopManager shopManager;

    public InfoCommand(WooSimMarket plugin, Messages messages) {
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

        for (Shop shop : shops) {
            messages.send(player, "command-info", "id", shop.id(),
                    "balance", String.format("%.2f", shop.balance()));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
