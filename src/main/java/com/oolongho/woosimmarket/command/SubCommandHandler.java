package com.oolongho.woosimmarket.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 子命令处理器接口。
 *
 * <p>每个子命令（如 reload/info/list/give/help）实现此接口，
 * 由 {@link MainCommand} 根据第一个参数分发调用。</p>
 *
 * @author oolongho
 */
public interface SubCommandHandler {

    /**
     * 执行子命令。
     *
     * @param sender 发送者
     * @param args   子命令参数（不含子命令名本身）
     * @return 消费成功返回 true，失败返回 false
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Tab 补全建议。
     *
     * @param sender 发送者
     * @param args   子命令参数（不含子命令名本身）
     * @return 补全建议列表；无建议返回空列表
     */
    List<String> tabComplete(CommandSender sender, String[] args);
}
