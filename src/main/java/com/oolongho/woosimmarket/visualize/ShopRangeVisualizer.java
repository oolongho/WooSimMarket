package com.oolongho.woosimmarket.visualize;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.model.Shop;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 商店绑定半径可视化器 —— 以粒子圆环展示收银机方块的绑定范围。
 *
 * <p>在收银机方块中心 {@code (x+0.5, y+0.5, z+0.5)} 周围，以
 * {@link ConfigLoader#getShopBindRadius()} 为半径生成抹茶绿 ({@code #a3b547})
 * 的 DUST 粒子圆环，每 0.5s (10 tick) 重绘一次，持续指定秒数后自动取消调度。</p>
 *
 * <p>调度使用 {@link BukkitRunnable#runTaskTimer}，匿名内部类持有帧计数器，
 * 达到 {@code seconds * 2} 次后 {@code cancel()}，避免任务泄漏。</p>
 *
 * <p>{@link DustOptions} 内部 {@link Color} 与 size 均不可变，复用单例常量即可。</p>
 *
 * @author oolongho
 */
public class ShopRangeVisualizer {

    /** 圆环等分点数。 */
    private static final int POINTS = 64;

    /** 调度间隔 (tick)，10 tick = 0.5s。 */
    private static final long PERIOD_TICKS = 10L;

    /** 抹茶绿粒子色 (匹配插件品牌)。 */
    private static final Color COLOR = Color.fromRGB(0xa3, 0xb5, 0x47);

    /** 粒子 dust 尺寸。 */
    private static final float DUST_SIZE = 1.5f;

    /** 复用的 DustOptions 实例 (Color 与 size 均不可变，可安全共享)。 */
    private static final DustOptions DUST_OPTIONS = new DustOptions(COLOR, DUST_SIZE);

    private final WooSimMarket plugin;
    private final ConfigLoader configLoader;

    public ShopRangeVisualizer(WooSimMarket plugin, ConfigLoader configLoader) {
        this.plugin = plugin;
        this.configLoader = configLoader;
    }

    /**
     * 以默认时长展示商店绑定半径。
     *
     * <p>时长由 {@link ConfigLoader#getRangeDurationSeconds()} 提供 (Task 4 新增)。</p>
     *
     * @param player 观察玩家
     * @param shop   商店
     */
    public void showRange(Player player, Shop shop) {
        showRange(player, shop, configLoader.getRangeDurationSeconds());
    }

    /**
     * 以指定秒数展示商店绑定半径。
     *
     * <p>在收银机方块中心周围以 {@link ConfigLoader#getShopBindRadius()} 为半径生成
     * 64 点 DUST 粒子圆环；每 0.5s 重绘一次，累计 {@code seconds * 2} 帧后自动
     * {@link BukkitRunnable#cancel()}。</p>
     *
     * @param player  观察玩家
     * @param shop    商店
     * @param seconds 持续秒数
     */
    public void showRange(Player player, Shop shop, int seconds) {
        final double cx = shop.x() + 0.5;
        final double cy = shop.y() + 0.5;
        final double cz = shop.z() + 0.5;
        final double r = configLoader.getShopBindRadius();
        final int maxFrames = seconds * 2;

        new BukkitRunnable() {
            private int frame = 0;

            @Override
            public void run() {
                for (int i = 0; i < POINTS; i++) {
                    double angle = 2 * Math.PI * i / POINTS;
                    double px = cx + r * Math.cos(angle);
                    double pz = cz + r * Math.sin(angle);
                    player.getWorld().spawnParticle(Particle.DUST, px, cy, pz, 1, DUST_OPTIONS);
                }
                if (++frame >= maxFrames) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, PERIOD_TICKS);
    }
}
