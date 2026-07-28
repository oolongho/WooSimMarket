package com.oolonghoo.woosimmarket.command;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.hook.CraftEngineHook;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * /wsm give <player> <cash_register|shelf> 子命令：给予玩家可放置的收银机/货架物品。
 *
 * <p>权限：woosimmarket.admin（默认 OP）。
 * 物品通过 {@link CraftEngineHook#createBlockItemStack(String)} 获取，
 * 背包满时掉落在玩家脚下。</p>
 *
 * @author oolongho
 */
public class GiveCommand implements SubCommandHandler {

    private static final List<String> BLOCK_TYPES = List.of("cash_register", "shelf");

    private final WooSimMarket plugin;
    private final Messages messages;
    private final CraftEngineHook craftEngine;

    public GiveCommand(WooSimMarket plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.craftEngine = plugin.getCraftEngineHook();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woosimmarket.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "command-help");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(sender, "player-only");
            return true;
        }

        String blockType = args[1].toLowerCase();
        if (!BLOCK_TYPES.contains(blockType)) {
            messages.send(sender, "command-help");
            return true;
        }

        ItemStack item = craftEngine.createBlockItemStack(blockType);
        if (item == null) {
            messages.send(sender, "command-help");
            return true;
        }

        var leftover = target.getInventory().addItem(item);
        leftover.values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        messages.send(sender, "command-give-success", "item", blockType, "player", target.getName());
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woosimmarket.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            return BLOCK_TYPES.stream()
                    .filter(type -> type.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
