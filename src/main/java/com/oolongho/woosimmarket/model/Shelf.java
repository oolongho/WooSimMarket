package com.oolongho.woosimmarket.model;

import com.oolongho.woosimmarket.database.DatabaseManager.ShelfRecord;
import com.oolongho.woosimmarket.util.SerializationUtils;
import org.bukkit.inventory.ItemStack;

/**
 * 货架领域模型（可变）。
 *
 * <p>{@code itemStack}、{@code price}、{@code stock}、{@code enabled}、{@code itemId} 可变，其余不可变。
 * 与 {@link ShelfRecord} 双向转换：序列化时 ItemStack → Base64，反序列化时 Base64 → ItemStack。</p>
 *
 * @author oolongho
 */
public class Shelf {

    /** 默认最大库存（9 组物品堆叠数，对应货架 GUI 9 个商品槽）。 */
    public static final int DEFAULT_MAX_STOCK = 576;

    private final String id;
    private final String shopId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String facing;
    private ItemStack itemStack;
    private double price;
    private int stock;
    private final int maxStock;
    private boolean enabled;
    private String itemId;

    public Shelf(String id, String shopId, String world, int x, int y, int z,
                 String facing, ItemStack itemStack, double price, int stock, int maxStock, boolean enabled,
                 String itemId) {
        this.id = id;
        this.shopId = shopId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.itemStack = itemStack;
        this.price = price;
        this.stock = stock;
        this.maxStock = maxStock > 0 ? maxStock : DEFAULT_MAX_STOCK;
        this.enabled = enabled;
        this.itemId = itemId;
    }

    /**
     * 从 DAO record 构造领域模型。ItemStack 从 Base64 反序列化。
     *
     * @param record 数据库记录
     * @return Shelf 实例
     */
    public static Shelf fromRecord(ShelfRecord record) {
        return new Shelf(
                record.id(),
                record.shopId(),
                record.world(),
                record.x(),
                record.y(),
                record.z(),
                record.facing(),
                SerializationUtils.deserializeItemStack(record.itemStackBase64()),
                record.price(),
                record.stock(),
                record.maxStock(),
                record.enabled(),
                record.itemId()
        );
    }

    /**
     * 转换为 DAO record 以持久化。ItemStack 序列化为 Base64。
     *
     * @return ShelfRecord 实例
     */
    public ShelfRecord toRecord() {
        return new ShelfRecord(
                id, shopId, world, x, y, z, facing,
                SerializationUtils.serializeItemStack(itemStack),
                price, stock, maxStock, enabled, itemId
        );
    }

    /**
     * 生成位置键（与 LocationUtils.serializeBlock 格式一致）。
     *
     * @return {@code world,x,y,z}
     */
    public String locationKey() {
        return world + "," + x + "," + y + "," + z;
    }

    /**
     * 是否有库存可售。
     *
     * @return 有库存且已启用返回 true
     */
    public boolean canSell() {
        return enabled && stock > 0 && itemStack != null && !itemStack.getType().isAir();
    }

    /**
     * 扣减库存（NPC 购买）。扣减后不低于 0。
     *
     * @param amount 扣减数量
     * @return 实际扣减数量
     */
    public int deductStock(int amount) {
        int actual = Math.min(this.stock, Math.max(0, amount));
        this.stock -= actual;
        return actual;
    }

    public String id() {
        return id;
    }

    public String shopId() {
        return shopId;
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

    public ItemStack itemStack() {
        return itemStack;
    }

    public void itemStack(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public double price() {
        return price;
    }

    public void price(double price) {
        this.price = Double.isFinite(price) ? Math.max(0, price) : 0;
    }

    public int stock() {
        return stock;
    }

    public void stock(int stock) {
        this.stock = Math.max(0, Math.min(stock, maxStock));
    }

    public int maxStock() {
        return maxStock;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String itemId() {
        return itemId;
    }

    public void itemId(String itemId) {
        this.itemId = itemId;
    }
}
