package com.oolongho.woosimmarket.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * 序列化工具类 —— ItemStack 与 Base64 字符串互转。
 *
 * <p>采用 Paper 26.1+ 原生 API：
 * <ul>
 *   <li>{@link ItemStack#serializeAsBytes()} 实例方法，输出字节流</li>
 *   <li>{@link ItemStack#deserializeBytes(byte[])} 静态方法，从字节流还原</li>
 * </ul>
 * 相比经典方案 {@code BukkitObjectOutputStream} 包装，原生 API 更简、更快、无 NMS 反射开销，
 * 且完整保留物品 NBT（含 CraftEngine 自定义方块状态、CustomModelData、display 等）。</p>
 *
 * <p>所有方法 null 安全：null 或 air 物品序列化为 {@code null}；null 或非法 Base64 反序列化为 {@code null}。</p>
 *
 * @author oolongho
 */
public final class SerializationUtils {

    private SerializationUtils() {
    }

    /**
     * 将 ItemStack 序列化为 Base64 字符串。
     *
     * @param item 物品栈，可为 null
     * @return Base64 编码字符串；入参为 null 或 air 时返回 null
     */
    public static String serializeItemStack(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        byte[] bytes = item.serializeAsBytes();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 将 Base64 字符串反序列化为 ItemStack。
     *
     * @param base64 Base64 编码字符串，可为 null
     * @return ItemStack 实例；入参为 null、空串或非法数据时返回 null
     */
    public static ItemStack deserializeItemStack(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
