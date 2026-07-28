package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /wsm help 子命令：显示帮助信息。
 *
 * @author oolongho
 */
public class HelpCommand implements SubCommandHandler {

    private final WooSimMarket plugin;
    private final Messages messages;

    public HelpCommand(WooSimMarket plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        messages.send(sender, "command-help");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
