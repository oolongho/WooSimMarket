package com.oolonghoo.woosimmarket.shop;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.database.ShopDao;
import com.oolonghoo.woosimmarket.database.ShelfDao;
import com.oolonghoo.woosimmarket.hook.CraftEngineHook;
import com.oolonghoo.woosimmarket.model.Shop;
import com.oolonghoo.woosimmarket.model.Shelf;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 商店与货架管理器。
 *
 * <p>维护内存索引（ID → 对象、位置键 → 对象），提供创建/绑定/删除/查询/余额操作。
 * 所有写操作同步落库（DAO 层有 ReentrantLock 保护 SQLite 单连接）。</p>
 *
 * <p>线程模型：主要在主线程（事件处理）调用；{@link #loadAll()} 在 onEnable 主线程执行。
 * 内存 Map 用 {@link ConcurrentHashMap} 保证可见性。</p>
 *
 * <p>位置键格式：{@code world,x,y,z}（与 {@link Shop#locationKey()} 一致）。</p>
 *
 * @author oolongho
 */
public class ShopManager {

    private final WooSimMarket plugin;
    private final ShopDao shopDao;
    private final ShelfDao shelfDao;
    private final CraftEngineHook craftEngine;

    private final ConcurrentHashMap<String, Shop> shopsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Shelf> shelvesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Shop> shopsByLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Shelf> shelvesByLocation = new ConcurrentHashMap<>();

    /** 玩家累计销售次数（内存计数，重启清空；用于 PlaceholderAPI 占位符）。 */
    private final ConcurrentHashMap<UUID, AtomicInteger> salesCountByPlayer = new ConcurrentHashMap<>();

    public ShopManager(WooSimMarket plugin, ShopDao shopDao, ShelfDao shelfDao,
                       CraftEngineHook craftEngine) {
        this.plugin = plugin;
        this.shopDao = shopDao;
        this.shelfDao = shelfDao;
        this.craftEngine = craftEngine;
    }

    // ===== 加载与重载 =====

    /**
     * 从数据库加载所有 Shop/Shelf，校验方块是否仍存在，失效记录惰性清除。
     *
     * <p>流程：加载 Shop → 校验方块（不存在则级联删除） → 加载 Shelf → 校验方块和 Shop 归属。</p>
     */
    public void loadAll() {
        List<Shop> shops = shopDao.loadAll().stream().map(Shop::fromRecord).toList();
        int shopCount = 0;
        int shopExpired = 0;
        for (Shop shop : shops) {
            if (!isBlockPresent(shop.world(), shop.x(), shop.y(), shop.z())) {
                shopDao.delete(shop.id());
                shopExpired++;
                continue;
            }
            registerShop(shop);
            shopCount++;
        }

        List<Shelf> shelves = shelfDao.loadAll().stream().map(Shelf::fromRecord).toList();
        int shelfCount = 0;
        int shelfExpired = 0;
        for (Shelf shelf : shelves) {
            if (!isBlockPresent(shelf.world(), shelf.x(), shelf.y(), shelf.z())) {
                shelfDao.delete(shelf.id());
                shelfExpired++;
                continue;
            }
            if (!shopsById.containsKey(shelf.shopId())) {
                shelfDao.delete(shelf.id());
                shelfExpired++;
                continue;
            }
            registerShelf(shelf);
            shelfCount++;
        }

        plugin.getLogger().info(String.format(
                "已加载 %d 个商店、%d 个货架（清除失效：商店 %d、货架 %d）",
                shopCount, shelfCount, shopExpired, shelfExpired));
    }

    /**
     * 重载：清空内存并重新加载。
     */
    public void reload() {
        clear();
        loadAll();
    }

    /**
     * 清空所有内存索引。
     */
    public void clear() {
        shopsById.clear();
        shelvesById.clear();
        shopsByLocation.clear();
        shelvesByLocation.clear();
    }

    // ===== 创建与删除 =====

    /**
     * 创建商店。调用方应先校验上限（{@link #countShopsByOwner}）和距离（{@link #isShopNear}）。
     *
     * @param ownerUuid 拥有者 UUID
     * @param world     世界名
     * @param x         方块 x
     * @param y         方块 y
     * @param z         方块 z
     * @param facing    朝向枚举名
     * @return 创建的 Shop；落库失败返回 null
     */
    public Shop createShop(UUID ownerUuid, String world, int x, int y, int z, String facing) {
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        Shop shop = new Shop(id, ownerUuid, world, x, y, z, facing, 0, createdAt);
        if (!shopDao.insert(shop.toRecord())) {
            return null;
        }
        registerShop(shop);
        return shop;
    }

    /**
     * 绑定货架到指定商店。调用方应先校验半径（{@link #findNearestShop}）。
     *
     * @param shopId 商店 ID
     * @param world  世界名
     * @param x      方块 x
     * @param y      方块 y
     * @param z      方块 z
     * @param facing 朝向枚举名
     * @return 创建的 Shelf；落库失败返回 null
     */
    public Shelf bindShelf(String shopId, String world, int x, int y, int z, String facing) {
        String id = UUID.randomUUID().toString();
        Shelf shelf = new Shelf(id, shopId, world, x, y, z, facing,
                null, 0, 0, Shelf.DEFAULT_MAX_STOCK, true);
        if (!shelfDao.insert(shelf.toRecord())) {
            return null;
        }
        registerShelf(shelf);
        return shelf;
    }

    /**
     * 删除商店及其所有关联货架（DAO 层 ON DELETE CASCADE 会自动清除货架记录，
     * 但内存需手动移除）。
     *
     * @param id 商店 ID
     * @return 删除成功返回 true
     */
    public boolean removeShop(String id) {
        Shop shop = shopsById.remove(id);
        if (shop == null) {
            return false;
        }
        shopsByLocation.remove(shop.locationKey());

        // 移除关联货架的内存索引
        for (Shelf shelf : new ArrayList<>(shelvesById.values())) {
            if (id.equals(shelf.shopId())) {
                shelvesById.remove(shelf.id());
                shelvesByLocation.remove(shelf.locationKey());
            }
        }

        return shopDao.delete(id);
    }

    /**
     * 删除单个货架。
     *
     * @param id 货架 ID
     * @return 删除成功返回 true
     */
    public boolean removeShelf(String id) {
        Shelf shelf = shelvesById.remove(id);
        if (shelf == null) {
            return false;
        }
        shelvesByLocation.remove(shelf.locationKey());
        return shelfDao.delete(id);
    }

    // ===== 查询 =====

    public Shop getShop(String id) {
        return shopsById.get(id);
    }

    public Shelf getShelf(String id) {
        return shelvesById.get(id);
    }

    /**
     * 按方块位置查询商店。
     *
     * @param world 世界名
     * @param x     方块 x
     * @param y     方块 y
     * @param z     方块 z
     * @return 商店；不存在返回 null
     */
    public Shop getShopAt(String world, int x, int y, int z) {
        return shopsByLocation.get(world + "," + x + "," + y + "," + z);
    }

    /**
     * 按方块位置查询货架。
     */
    public Shelf getShelfAt(String world, int x, int y, int z) {
        return shelvesByLocation.get(world + "," + x + "," + y + "," + z);
    }

    /**
     * 获取商店关联的所有货架。
     *
     * @param shopId 商店 ID
     * @return 货架列表（不可变副本）；商店不存在返回空列表
     */
    public List<Shelf> getShelvesByShop(String shopId) {
        List<Shelf> result = new ArrayList<>();
        for (Shelf shelf : shelvesById.values()) {
            if (shopId.equals(shelf.shopId())) {
                result.add(shelf);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 统计玩家拥有的商店数量。
     *
     * @param ownerUuid 玩家 UUID
     * @return 商店数量
     */
    public int countShopsByOwner(UUID ownerUuid) {
        int count = 0;
        for (Shop shop : shopsById.values()) {
            if (ownerUuid.equals(shop.ownerUuid())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查指定位置附近是否已有商店（最小距离校验）。
     *
     * @param world      世界名
     * @param x          方块 x
     * @param y          方块 y
     * @param z          方块 z
     * @param minDistance 最小距离（方块）
     * @return 距离不足返回 true
     */
    public boolean isShopNear(String world, int x, int y, int z, double minDistance) {
        double minDistSq = minDistance * minDistance;
        for (Shop shop : shopsById.values()) {
            if (!world.equals(shop.world())) {
                continue;
            }
            double dx = shop.x() - x;
            double dy = shop.y() - y;
            double dz = shop.z() - z;
            if (dx * dx + dy * dy + dz * dz < minDistSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找指定位置半径内的最近商店（用于货架绑定）。
     *
     * @param world  世界名
     * @param x      方块 x
     * @param y      方块 y
     * @param z      方块 z
     * @param radius 搜索半径
     * @return 最近的商店；无返回 null
     */
    public Shop findNearestShop(String world, int x, int y, int z, double radius) {
        double radiusSq = radius * radius;
        Shop nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Shop shop : shopsById.values()) {
            if (!world.equals(shop.world())) {
                continue;
            }
            double dx = shop.x() - x;
            double dy = shop.y() - y;
            double dz = shop.z() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= radiusSq && distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = shop;
            }
        }
        return nearest;
    }

    // ===== 余额操作 =====

    /**
     * 增加商店余额并落库。
     *
     * @param shop   商店
     * @param amount 金额
     */
    public void addBalance(Shop shop, double amount) {
        shop.addBalance(amount);
        shopDao.update(shop.toRecord());
    }

    /**
     * 提现：扣减商店余额并落库。
     *
     * @param shop   商店
     * @param amount 请求金额
     * @return 实际提现金额
     */
    public double withdraw(Shop shop, double amount) {
        double actual = shop.deductBalance(amount);
        if (actual > 0) {
            shopDao.update(shop.toRecord());
        }
        return actual;
    }

    // ===== 销售统计（PlaceholderAPI 数据源） =====

    /**
     * 记录一次成功的 NPC 购买（累计销售次数 +1）。
     *
     * <p>由 {@link com.oolonghoo.woosimmarket.npc.NpcManager#handlePurchase} 在购买成功后调用。
     * 不在 {@link #addBalance} 中调用，避免 EconomyManager 提现回滚误增计数。</p>
     *
     * @param ownerUuid 商店拥有者 UUID
     */
    public void recordSale(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        salesCountByPlayer.computeIfAbsent(ownerUuid, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 获取玩家累计销售次数（NPC 购买成功次数）。
     *
     * @param ownerUuid 玩家 UUID
     * @return 销售次数；无记录返回 0
     */
    public int getSalesCount(UUID ownerUuid) {
        if (ownerUuid == null) {
            return 0;
        }
        AtomicInteger count = salesCountByPlayer.get(ownerUuid);
        return count != null ? count.get() : 0;
    }

    /**
     * 获取玩家所有商店的余额总和。
     *
     * @param ownerUuid 玩家 UUID
     * @return 余额总和；无商店返回 0
     */
    public double getTotalBalance(UUID ownerUuid) {
        if (ownerUuid == null) {
            return 0;
        }
        double total = 0;
        for (Shop shop : shopsById.values()) {
            if (ownerUuid.equals(shop.ownerUuid())) {
                total += shop.balance();
            }
        }
        return total;
    }

    // ===== 持久化 =====

    /**
     * 保存货架到数据库。
     *
     * @param shelf 货架
     */
    public void saveShelf(Shelf shelf) {
        shelfDao.update(shelf.toRecord());
    }

    /**
     * 保存商店到数据库。
     *
     * @param shop 商店
     */
    public void saveShop(Shop shop) {
        shopDao.update(shop.toRecord());
    }

    // ===== 内部方法 =====

    private void registerShop(Shop shop) {
        shopsById.put(shop.id(), shop);
        shopsByLocation.put(shop.locationKey(), shop);
    }

    private void registerShelf(Shelf shelf) {
        shelvesById.put(shelf.id(), shelf);
        shelvesByLocation.put(shelf.locationKey(), shelf);
    }

    /**
     * 检查方块是否仍存在（CraftEngine 自定义方块校验）。
     * 世界未加载时返回 true（保留记录，等世界加载后再校验）。
     */
    private boolean isBlockPresent(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return true;
        }
        return craftEngine.isCustomBlock(world.getBlockAt(x, y, z));
    }

    public int getShopCount() {
        return shopsById.size();
    }

    public int getShelfCount() {
        return shelvesById.size();
    }

    /**
     * 获取所有商店（不可变副本）。
     *
     * @return 商店列表
     */
    public List<Shop> getAllShops() {
        return List.copyOf(shopsById.values());
    }

    /**
     * 获取所有货架（不可变副本）。
     *
     * @return 货架列表
     */
    public List<Shelf> getAllShelves() {
        return List.copyOf(shelvesById.values());
    }
}
