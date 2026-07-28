package com.oolonghoo.woosimmarket.command;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.model.Shop;
import com.oolonghoo.woosimmarket.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /wsm list [player] 子命令：列出商店。
 *
 * <p>不带参数：列出发送者的商店（仅玩家）。
 * 带参数：列出指定玩家的商店（需要 woosimmarket.admin 权限）。</p>
 *
 * @author oolongho
 */
public class ListCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;
    private final ShopManager shopManager;

    public ListCommand(WooSimMarket plugin, Messages messages) {
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

        UUID targetUuid;
        String targetName;

        if (args.length == 0) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                messages.send(sender, "player-only");
                return true;
            }
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        } else {
            if (!sender.hasPermission("woosimmarket.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            targetUuid = target.getUniqueId();
            targetName = target.getName() != null ? target.getName() : args[0];
        }

        List<Shop> shops = shopManager.getAllShops().stream()
                .filter(s -> s.ownerUuid().equals(targetUuid))
                .toList();

        if (shops.isEmpty()) {
            messages.send(sender, "shop-not-found");
            return true;
        }

        String shopsText = shops.stream()
                .map(s -> s.id() + "(" + String.format("%.2f", s.balance()) + ")")
                .collect(Collectors.joining(", "));
        messages.send(sender, "command-list", "player", targetName, "shops", shopsText);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission("woosimmarket.admin")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(org.bukkit.entity.Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
