package com.oolongho.woosimmarket.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * 位置工具类
 *
 * <p>处理 {@link Location} 的序列化/反序列化、距离计算与同世界判断。
 * 所有公共方法均处理 null 入参，绝不抛 NPE。
 * 序列化格式：完整位置 {@code world,x,y,z,yaw,pitch}（保留 2 位小数）；
 * 方块位置 {@code world,x,y,z}（整数方块坐标，用于方块持久化）。</p>
 *
 * @author oolongho
 */
public final class LocationUtils {

    private LocationUtils() {
    }

    /**
     * 将位置序列化为字符串。
     *
     * <p>格式：{@code world,x,y,z,yaw,pitch}（保留 2 位小数）。</p>
     *
     * @param location 位置
     * @return 序列化字符串；入参为 null 或无世界返回空串
     */
    public static String serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return String.format("%s,%.2f,%.2f,%.2f,%.2f,%.2f",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    /**
     * 将字符串反序列化为位置。
     *
     * <p>兼容 4 段（无 yaw/pitch）和 6 段格式；缺失的 yaw/pitch 默认 0。</p>
     *
     * @param serialized 序列化字符串
     * @return 位置；解析失败、世界不存在或入参为 null 返回 null
     */
    @Nullable
    public static Location deserialize(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length < 4) {
            return null;
        }
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return null;
            }
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将方块位置序列化为字符串（整数方块坐标）。
     *
     * <p>格式：{@code world,x,y,z}。用于方块位置的持久化。</p>
     *
     * @param location 位置
     * @return 序列化字符串；入参为 null 或无世界返回空串
     */
    public static String serializeBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return String.format("%s,%d,%d,%d",
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    /**
     * 将字符串反序列化为方块位置（整数坐标，yaw/pitch 为 0）。
     *
     * @param serialized 序列化字符串
     * @return 方块位置；解析失败、世界不存在或入参为 null 返回 null
     */
    @Nullable
    public static Location deserializeBlock(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length < 4) {
            return null;
        }
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return null;
            }
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判断两个位置是否在同一世界。
     *
     * @param loc1 位置1
     * @param loc2 位置2
     * @return 同世界返回 true；任一为 null 或无世界或异世界返回 false
     */
    public static boolean isSameWorld(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) {
            return false;
        }
        World w1 = loc1.getWorld();
        World w2 = loc2.getWorld();
        return w1 != null && w1.equals(w2);
    }

    /**
     * 计算两位置距离。异世界返回 {@link Double#MAX_VALUE}（不抛异常）。
     *
     * @param loc1 位置1
     * @param loc2 位置2
     * @return 距离；异世界或任一为 null 返回 {@link Double#MAX_VALUE}
     */
    public static double distance(Location loc1, Location loc2) {
        if (!isSameWorld(loc1, loc2)) {
            return Double.MAX_VALUE;
        }
        return loc1.distance(loc2);
    }

    /**
     * 计算两位置距离平方（性能优化用，避免开方）。异世界返回 {@link Double#MAX_VALUE}。
     *
     * @param loc1 位置1
     * @param loc2 位置2
     * @return 距离平方；异世界或任一为 null 返回 {@link Double#MAX_VALUE}
     */
    public static double distanceSquared(Location loc1, Location loc2) {
        if (!isSameWorld(loc1, loc2)) {
            return Double.MAX_VALUE;
        }
        return loc1.distanceSquared(loc2);
    }

    /**
     * 判断位置是否在中心点半径内。
     *
     * @param loc    待判断位置
     * @param center 中心点
     * @param radius 半径
     * @return 在半径内返回 true；异世界或任一为 null 返回 false
     */
    public static boolean isWithin(Location loc, Location center, double radius) {
        if (!isSameWorld(loc, center)) {
            return false;
        }
        return loc.distanceSquared(center) <= radius * radius;
    }

    /**
     * 格式化位置为可读字符串。
     *
     * <p>格式：{@code world (x, y, z)}（保留 1 位小数）。</p>
     *
     * @param location 位置
     * @return 可读字符串；入参为 null 或无世界返回 "Unknown"
     */
    public static String format(Location location) {
        if (location == null || location.getWorld() == null) {
            return "Unknown";
        }
        return String.format("%s (%.1f, %.1f, %.1f)",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ());
    }
}
