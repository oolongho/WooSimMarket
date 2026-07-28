package com.oolongho.woosimmarket.util;

import com.oolongho.woosimmarket.WooSimMarket;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * 调度工具类 —— 集中 {@link org.bukkit.scheduler.BukkitScheduler} 调度调用。
 *
 * <p>所有调度均通过本类发起，便于后续 Folia 适配（替换为
 * {@code regionScheduler} / {@code asyncScheduler} / {@code globalRegionScheduler}）。</p>
 *
 * <p>所有方法在插件禁用时自动取消任务（BukkitTask 绑定 plugin，onDisable 自动清理）。</p>
 *
 * @author oolongho
 */
public final class TaskUtil {

    private TaskUtil() {
    }

    /**
     * 在主线程同步执行任务。
     *
     * @param plugin   插件实例
     * @param runnable 任务
     * @return BukkitTask
     */
    public static BukkitTask run(WooSimMarket plugin, Runnable runnable) {
        return Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /**
     * 在主线程延迟执行任务。
     *
     * @param plugin   插件实例
     * @param runnable 任务
     * @param delay    延迟（tick）
     * @return BukkitTask
     */
    public static BukkitTask runLater(WooSimMarket plugin, Runnable runnable, long delay) {
        return Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
    }

    /**
     * 在异步线程执行任务。
     *
     * @param plugin   插件实例
     * @param runnable 任务
     * @return BukkitTask
     */
    public static BukkitTask runAsync(WooSimMarket plugin, Runnable runnable) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    /**
     * 在主线程定时重复执行任务。
     *
     * @param plugin   插件实例
     * @param runnable 任务
     * @param delay    首次延迟（tick）
     * @param period   重复间隔（tick）
     * @return BukkitTask
     */
    public static BukkitTask runAtFixed(WooSimMarket plugin, Runnable runnable, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period);
    }
}
