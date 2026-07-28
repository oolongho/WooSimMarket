package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm reload 子命令：重载插件配置与消息。
 *
 * <p>权限：woosimmarket.reload（默认 OP）。</p>
 *
 * @author oolongho
 */
public class ReloadCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;

    public ReloadCommand(WooSimMarket plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("woosimmarket.reload")) {
            messages.send(sender, "no-permission");
            return true;
        }
        plugin.reload();
        messages.send(sender, "config-reloaded");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
