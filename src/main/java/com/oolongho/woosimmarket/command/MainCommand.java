package com.oolongho.woosimmarket.command;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主命令分发器（/woosimmarket，别名 /wsm）。
 *
 * <p>根据第一个参数分发到对应的 {@link SubCommandHandler}：
 * reload/info/list/give/help。无参数时显示 help。</p>
 *
 * <p>Tab 补全：第一层补全子命令名，后续层转发到对应处理器的 tabComplete。</p>
 *
 * @author oolongho
 */
public class MainCommand implements CommandExecutor, TabCompleter {

    private final WooSimMarket plugin;
    private final Messages messages;
    private final Map<String, SubCommandHandler> handlers = new HashMap<>();

    public MainCommand(WooSimMarket plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        registerHandlers();
    }

    private void registerHandlers() {
        register("reload", new ReloadCommand(plugin, messages));
        register("info", new InfoCommand(plugin, messages));
        register("list", new ListCommand(plugin, messages));
        register("give", new GiveCommand(plugin, messages));
        register("range", new RangeCommand(plugin, messages));
        register("stats", new StatsCommand(plugin, messages));
        register("drift", new DriftCommand(plugin, messages));
        register("help", new HelpCommand(plugin, messages));
    }

    private void register(String name, SubCommandHandler handler) {
        handlers.put(name.toLowerCase(), handler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return dispatch(sender, "help", new String[0]);
        }
        String subName = args[0].toLowerCase();
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        return dispatch(sender, subName, subArgs);
    }

    private boolean dispatch(CommandSender sender, String subName, String[] subArgs) {
        SubCommandHandler handler = handlers.get(subName);
        if (handler == null) {
            messages.send(sender, "command-help");
            return true;
        }
        return handler.execute(sender, subArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            for (String name : handlers.keySet()) {
                if (name.startsWith(args[0].toLowerCase())) {
                    suggestions.add(name);
                }
            }
            return suggestions;
        }
        if (args.length >= 2) {
            String subName = args[0].toLowerCase();
            SubCommandHandler handler = handlers.get(subName);
            if (handler == null) {
                return List.of();
            }
            String[] subArgs = new String[args.length - 1];
            System.arraycopy(args, 1, subArgs, 0, subArgs.length);
            return handler.tabComplete(sender, subArgs);
        }
        return List.of();
    }
}
