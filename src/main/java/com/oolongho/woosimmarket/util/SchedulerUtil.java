package com.oolongho.woosimmarket.util;

import com.oolongho.woosimmarket.WooSimMarket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

/**
 * 统一调度工具类，兼容 Paper 和 Folia。
 *
 * <p>Folia 使用区域化线程调度器，与传统 Bukkit 的全局主线程模型不同。
 * 本类封装两种调度 API 的差异，使调用方无需关心运行环境。</p>
 *
 * <p>Folia 检测通过 {@code io.papermc.paper.threadedregions.scheduler.RegionScheduler}
 * 类存在性判断。{@link #initialize(WooSimMarket)} 必须在 {@code onEnable} 早期、
 * 任何调度调用前执行。</p>
 *
 * <p>所有方法返回 {@link TaskHandle} 以统一取消逻辑（Folia {@code ScheduledTask}
 * 与 {@link BukkitTask} 取消方式不同）。</p>
 *
 * @author oolongho
 */
public final class SchedulerUtil {

    private static WooSimMarket plugin;
    private static volatile boolean folia;

    private SchedulerUtil() {
    }

    /**
     * 初始化调度工具，必须在插件启动时调用（在任何调度调用前）。
     *
     * @param plugin 插件实例
     */
    public static void initialize(WooSimMarket plugin) {
        SchedulerUtil.plugin = plugin;
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
    }

    /**
     * 检测当前服务端是否为 Folia。
     *
     * @return 是否为 Folia
     */
    public static boolean isFolia() {
        return folia;
    }

    /**
     * 在主线程（Paper）或玩家所属区域线程（Folia）执行任务。
     *
     * @param player 玩家上下文
     * @param task   要执行的任务
     * @return 任务句柄
     */
    public static TaskHandle runTask(Player player, Runnable task) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    player.getScheduler().run(plugin, t -> task.run(), null);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, task);
        return new TaskHandle(bt, false);
    }

    /**
     * 在主线程（Paper）或全局区域线程（Folia）执行任务。
     * 适用于无实体上下文的操作，如控制台命令。
     *
     * @param task 要执行的任务
     * @return 任务句柄
     */
    public static TaskHandle runTask(Runnable task) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, task);
        return new TaskHandle(bt, false);
    }

    /**
     * 延迟执行任务（玩家上下文）。
     *
     * @param player     玩家上下文
     * @param task       要执行的任务
     * @param delayTicks 延迟 tick 数
     * @return 任务句柄
     */
    public static TaskHandle runTaskLater(Player player, Runnable task, long delayTicks) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    player.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 延迟执行任务（全局区域）。
     *
     * @param task       要执行的任务
     * @param delayTicks 延迟 tick 数
     * @return 任务句柄
     */
    public static TaskHandle runTaskLater(Runnable task, long delayTicks) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 在指定位置的区域线程执行任务（Folia），或在主线程执行（Paper）。
     *
     * @param location 位置上下文
     * @param task     要执行的任务
     */
    public static void runTaskAt(Location location, Runnable task) {
        if (isFolia()) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在实体所属区域线程执行任务（Folia），或在主线程执行（Paper）。
     *
     * <p>Folia 上实体的 {@code remove/teleport/text} 等操作必须在实体所属区域线程执行，
     * 否则会抛 {@link UnsupportedOperationException} 或静默失败。本方法通过
     * {@link Entity#getScheduler()} 路由，若实体已销毁则任务被取消（不执行）。</p>
     *
     * @param entity 实体上下文
     * @param task   要执行的任务
     * @return 任务句柄；Paper 路径返回非 null，Folia 路径在实体已销毁时返回 null
     */
    public static TaskHandle runTask(Entity entity, Runnable task) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    entity.getScheduler().run(plugin, t -> task.run(), null);
            return st != null ? new TaskHandle(st, true) : null;
        }
        BukkitTask bt = Bukkit.getScheduler().runTask(plugin, task);
        return new TaskHandle(bt, false);
    }

    /**
     * 同步执行实体操作：Paper 直接调用（假设已在主线程），Folia 路由到实体所属区域线程。
     *
     * <p>语义：调用方承诺此操作需要立即执行（如 onEnable init cleanup、onDisable clearAll、
     * 事件处理 remove）。Paper 路径假设已在主线程，直接 {@code task.run()}；
     * 而 {@link #runTask(Entity, Runnable)} 在 onDisable 时会抛 {@link IllegalStateException}，
     * 本方法在 onDisable 时安全（主线程仍在运行）。</p>
     *
     * <p>Folia 路径用 {@link Entity#getScheduler()} 路由，实体已销毁时任务被取消（不执行）。</p>
     *
     * <p>与 {@link #runTask(Entity, Runnable)} 的区别：
     * <ul>
     *   <li>{@code runTask}：Paper 路径用 {@link Bukkit#getScheduler()} 异步下一 tick；onDisable 抛异常</li>
     *   <li>{@code execute}：Paper 路径直接同步执行；onDisable 安全</li>
     * </ul></p>
     *
     * @param entity 实体上下文
     * @param task   要执行的任务
     */
    public static void execute(Entity entity, Runnable task) {
        if (isFolia()) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            task.run();
        }
    }

    /**
     * 延迟在实体所属区域线程执行任务（Folia），或主线程延迟执行（Paper）。
     *
     * <p>用于实体销毁回调和定时实体操作。Folia 上若实体在延迟期间销毁，
     * 任务自动取消（不执行）。</p>
     *
     * @param entity     实体上下文
     * @param task       要执行的任务
     * @param delayTicks 延迟 tick 数
     * @return 任务句柄；Paper 路径返回非 null，Folia 路径在实体已销毁时返回 null
     */
    public static TaskHandle runTaskLater(Entity entity, Runnable task, long delayTicks) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    entity.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks);
            return st != null ? new TaskHandle(st, true) : null;
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 以固定间隔在指定位置的区域线程重复执行任务（Folia），或主线程重复执行（Paper）。
     *
     * <p>用于需要按位置上下文执行的周期任务（如粒子展示）。
     * Folia 路径通过 {@link org.bukkit.RegionScheduler}，每次执行都在
     * 该位置当前所属的区域线程（区块跨区域时自动迁移）。</p>
     *
     * @param location          位置上下文
     * @param task              要执行的任务
     * @param initialDelayTicks 初始延迟 tick 数
     * @param periodTicks        执行间隔 tick 数
     * @return 任务句柄
     */
    public static TaskHandle runAtFixedRate(Location location, Runnable task,
                                             long initialDelayTicks, long periodTicks) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getRegionScheduler().runAtFixedRate(
                            plugin, location, t -> task.run(), initialDelayTicks, periodTicks);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 异步执行任务。
     *
     * @param task 要执行的任务
     * @return 任务句柄
     */
    public static TaskHandle runTaskAsynchronously(Runnable task) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        return new TaskHandle(bt, false);
    }

    /**
     * 以固定间隔重复执行任务（主线程 / 全局区域），返回可取消的任务句柄。
     *
     * @param task              要执行的任务
     * @param initialDelayTicks 初始延迟 tick 数
     * @param periodTicks        执行间隔 tick 数
     * @return 任务句柄
     */
    public static TaskHandle runAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        if (isFolia()) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                            plugin, t -> task.run(), initialDelayTicks, periodTicks);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 异步以固定间隔重复执行任务。
     *
     * <p>Folia 的 {@code AsyncScheduler} 接受真实时间单位而非 tick，故将 tick 参数
     * 按 {@code 1 tick = 50ms} 转换为毫秒。Paper 路径仍按 tick 计。</p>
     *
     * @param task              要执行的任务
     * @param initialDelayTicks 初始延迟 tick 数
     * @param periodTicks        执行间隔 tick 数
     * @return 任务句柄
     */
    public static TaskHandle runAsyncAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        if (isFolia()) {
            long initialDelayMs = Math.max(0L, initialDelayTicks) * 50L;
            long periodMs = Math.max(1L, periodTicks) * 50L;
            io.papermc.paper.threadedregions.scheduler.ScheduledTask st =
                    Bukkit.getAsyncScheduler().runAtFixedRate(
                            plugin, t -> task.run(), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
            return new TaskHandle(st, true);
        }
        BukkitTask bt = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, task, initialDelayTicks, periodTicks);
        return new TaskHandle(bt, false);
    }

    /**
     * 统一任务句柄，封装 Folia {@code ScheduledTask} 和 {@link BukkitTask} 的取消逻辑。
     */
    public static final class TaskHandle {

        private final Object task;
        private final boolean folia;

        private TaskHandle(Object task, boolean folia) {
            this.task = task;
            this.folia = folia;
        }

        /**
         * 取消任务。
         */
        public void cancel() {
            if (folia) {
                ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) task).cancel();
            } else {
                Bukkit.getScheduler().cancelTask(((BukkitTask) task).getTaskId());
            }
        }
    }
}
