package com.oolongho.woosimmarket.model;

import com.oolongho.woosimmarket.database.DatabaseManager.ShopRecord;

import java.util.UUID;

/**
 * 商店领域模型（可变）。
 *
 * <p>{@code balance} 与 {@code name} 可变，其余字段不可变。与 {@link ShopRecord} 双向转换：
 * {@link #fromRecord(ShopRecord)} 构造实例，{@link #toRecord()} 序列化为 DAO 入参。</p>
 *
 * <p>位置以 world + x + y + z 整数方块坐标存储，facing 存枚举名字符串（NORTH/SOUTH/EAST/WEST）。</p>
 *
 * @author oolongho
 */
public class Shop {

    private final String id;
    private final UUID ownerUuid;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String facing;
    private double balance;
    private final long createdAt;
    private String name;

    public Shop(String id, UUID ownerUuid, String world, int x, int y, int z,
                String facing, double balance, long createdAt, String name) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.balance = balance;
        this.createdAt = createdAt;
        this.name = name;
    }

    /**
     * 从 DAO record 构造领域模型。
     *
     * @param record 数据库记录
     * @return Shop 实例
     */
    public static Shop fromRecord(ShopRecord record) {
        return new Shop(
                record.id(),
                record.ownerUuid(),
                record.world(),
                record.x(),
                record.y(),
                record.z(),
                record.facing(),
                record.balance(),
                record.createdAt(),
                record.name()
        );
    }

    /**
     * 转换为 DAO record 以持久化。
     *
     * @return ShopRecord 实例
     */
    public ShopRecord toRecord() {
        return new ShopRecord(id, ownerUuid, world, x, y, z, facing, balance, createdAt, name);
    }

    /**
     * 生成位置键（与 LocationUtils.serializeBlock 格式一致）。
     *
     * @return {@code world,x,y,z}
     */
    public String locationKey() {
        return world + "," + x + "," + y + "," + z;
    }

    public String id() {
        return id;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String facing() {
        return facing;
    }

    public double balance() {
        return balance;
    }

    public long createdAt() {
        return createdAt;
    }

    public String name() {
        return name;
    }

    /**
     * 设置店名（trim 后非空才写入）。null/空白忽略。
     *
     * @param name 新店名
     */
    public void name(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    /**
     * 增加余额（NPC 购买进账）。使用 {@code Math.max(0, ...)} 兜底防止负数。
     * NaN/Infinity 入参视为 0（无操作）。
     *
     * @param amount 增加金额
     */
    public void addBalance(double amount) {
        if (!Double.isFinite(amount)) {
            return;
        }
        this.balance = Math.max(0, this.balance + amount);
    }

    /**
     * 扣减余额（提现）。扣减后余额不低于 0。
     * NaN/Infinity 入参返回 0（无操作）。
     *
     * @param amount 扣减金额
     * @return 实际扣减金额（若余额不足则扣至 0）
     */
    public double deductBalance(double amount) {
        if (!Double.isFinite(amount)) {
            return 0;
        }
        double actual = Math.min(this.balance, Math.max(0, amount));
        this.balance -= actual;
        return actual;
    }
}
