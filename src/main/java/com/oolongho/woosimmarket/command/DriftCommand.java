package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm drift 子命令：手动触发漂移相关维护操作。
 *
 * <p>当前仅支持 {@code /wsm drift recompute}：异步重算所有物品的 priceDrift。
 * 权限：woosimmarket.admin（默认 OP）。</p>
 *
 * @author oolongho
 */
public class DriftCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;

    public DriftCommand(WooSimMarket plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woosimmarket.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "command-help");
            return true;
        }
        if (args[0].equalsIgnoreCase("recompute")) {
            plugin.getMarketManager().recomputeDriftNow();
            messages.send(sender, "command-drift-recompute");
            return true;
        }
        messages.send(sender, "command-help");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && "recompute".startsWith(args[0].toLowerCase())) {
            return List.of("recompute");
        }
        return List.of();
    }
}
