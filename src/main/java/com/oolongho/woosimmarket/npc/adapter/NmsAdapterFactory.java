package com.oolongho.woosimmarket.npc.adapter;

import com.oolongho.woosimmarket.npc.adapter.v1_21_0.LegacyNmsAdapter;
import com.oolongho.woosimmarket.npc.adapter.v1_21_2.ModernNmsAdapter;
import org.bukkit.Bukkit;

/**
 * NMS 适配器工厂。
 *
 * <p>运行时根据 {@code Bukkit.getServer().getBukkitVersion()} 解析版本号，
 * 在 1.21.0/1 与 1.21.2+（含 Spigot 26.1+）之间选择实现：</p>
 * <ul>
 *   <li>{@code major==1 && minor==21 && patch<=1} → {@link LegacyNmsAdapter}</li>
 *   <li>其他（含 1.21.2+ 与 26.1+） → {@link ModernNmsAdapter}</li>
 * </ul>
 *
 * <p>选择逻辑与 WooHolograms 的 {@code EntityPacketHelperFactory} 完全一致，确保单 jar
 * 跨版本兼容的判定规则统一。选型结果仅记录 INFO 日志，不抛异常 —— 解析失败回退 Modern。</p>
 *
 * @author oolongho
 */
public final class NmsAdapterFactory {

    private static final NmsAdapter INSTANCE = create();

    public static NmsAdapter getInstance() {
        return INSTANCE;
    }

    private static NmsAdapter create() {
        String version = Bukkit.getServer().getBukkitVersion();
        int dashIdx = version.indexOf('-');
        if (dashIdx > 0) {
            version = version.substring(0, dashIdx);
        }
        String[] parts = version.split("\\.");

        int major = 0, minor = 0, patch = 0;
        try {
            if (parts.length >= 1) major = Integer.parseInt(parts[0]);
            if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
            if (parts.length >= 3) patch = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ignored) {
            // 解析失败回退 Modern（覆盖更多版本且 API 已稳定）
        }

        boolean legacy = (major == 1 && minor == 21 && patch <= 1);
        String adapterType = legacy ? "legacy" : "modern";
        Bukkit.getLogger().info("[WooSimMarket] NMS adapter: " + adapterType
                + " (server version: " + version + ")");

        return legacy ? new LegacyNmsAdapter() : new ModernNmsAdapter();
    }
}
