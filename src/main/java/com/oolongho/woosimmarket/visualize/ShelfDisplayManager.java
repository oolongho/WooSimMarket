package com.oolongho.woosimmarket.visualize;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 货架全息展示管理器。
 *
 * <p>在货架方块上方生成 {@link ItemDisplay}（物品模型），
 * 由事件驱动刷新。所有展示实体携带 scoreboard tag {@value #SCOREBOARD_TAG}
 * 与 PDC 键 {@code shelfId}，便于崩溃残留清理与溯源。</p>
 *
 * <p>线程模型：所有方法必须在主线程调用（实体生成/移除为主线程操作）。
 * 内存索引使用 {@link ConcurrentHashMap} 保证可见性。</p>
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #init} —— onEnable 调用，清理残留并为已加载区块生成展示</li>
 *   <li>{@link #onChunkLoad} —— 由 ChunkLoadEvent 触发，补齐未加载区块的展示</li>
 *   <li>{@link #refreshShelf} —— 由货架变更事件触发，幂等刷新</li>
 *   <li>{@link #clearAll} —— onDisable 调用，移除所有展示实体</li>
 * </ul></p>
 *
 * @author oolongho
 */
public class ShelfDisplayManager {

    /** Scoreboard tag，标记所有由本管理器生成的展示实体。 */
    private static final String SCOREBOARD_TAG = "woosimmarket_shelf_display";

    /** 展示实体统一缩放比例。 */
    private static final float DISPLAY_SCALE = 0.5f;

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final ConfigLoader configLoader;
    private final NamespacedKey shelfIdKey;

    /** shelfId → 展示句柄（ItemDisplay 的 UUID）。 */
    private final Map<String, DisplayHandle> handlesByShelfId = new ConcurrentHashMap<>();

    /** 展示句柄：物品展示实体的 UUID。 */
    private record DisplayHandle(UUID itemDisplayUuid) {}

    public ShelfDisplayManager(WooSimMarket plugin, ShopManager shopManager, ConfigLoader configLoader) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.configLoader = configLoader;
        this.shelfIdKey = new NamespacedKey(plugin, "shelfId");
    }

    /**
     * 插件 onEnable 调用：清理所有已加载世界中的残留展示实体，
     * 然后为已加载区块内的合格货架生成展示。
     */
    public void init() {
        // 清理上次崩溃残留的展示实体（遍历所有已加载世界）
        // Folia 上 entity.remove 必须在实体所属区域线程执行，用 execute 路由
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                    SchedulerUtil.execute(entity, entity::remove);
                }
            }
        }
        handlesByShelfId.clear();

        // 为合格货架生成展示（区块未加载时跳过，等 ChunkLoadEvent 补齐）
        for (Shelf shelf : shopManager.getAllShelves()) {
            if (!qualifies(shelf)) {
                continue;
            }
            World world = Bukkit.getWorld(shelf.world());
            if (world == null || !world.isChunkLoaded(shelf.x() >> 4, shelf.z() >> 4)) {
                continue;
            }
            spawnDisplay(shelf);
        }
    }

    /**
     * 在货架方块上方生成 ItemDisplay。
     *
     * <p>调用方应已校验 {@link #qualifies} 与区块加载状态。
     * 使用 {@link World#spawn(Location, Class, java.util.function.Consumer)}
     * 在实体加入世界前完成全部配置。</p>
     *
     * @param shelf 货架
     */
    private void spawnDisplay(Shelf shelf) {
        World world = Bukkit.getWorld(shelf.world());
        if (world == null) {
            return;
        }

        double itemOffsetY = configLoader.getShelfDisplayItemOffsetY();
        Location itemLoc = new Location(world,
                shelf.x() + 0.5, shelf.y() + itemOffsetY, shelf.z() + 0.5);

        // ItemDisplay：物品模型展示，billboard 仅水平旋转朝向玩家（客户端原生处理，零卡顿）
        ItemDisplay itemDisplay = world.spawn(itemLoc, ItemDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.VERTICAL);
            entity.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                    new Quaternionf()));
            entity.setItemStack(shelf.itemStack().clone());
            markDisplayEntity(entity, shelf.id());
        });

        handlesByShelfId.put(shelf.id(), new DisplayHandle(itemDisplay.getUniqueId()));
    }

    /**
     * 刷新货架展示（幂等，事件驱动入口）。
     *
     * <p>先移除现有展示，再根据货架当前状态决定是否重新生成。
     * 区块未加载时仅移除不生成（等 ChunkLoadEvent 补齐）。</p>
     *
     * @param shelf 货架
     */
    public void refreshShelf(Shelf shelf) {
        removeDisplay(shelf.id());
        if (!qualifies(shelf)) {
            return;
        }
        World world = Bukkit.getWorld(shelf.world());
        if (world == null || !world.isChunkLoaded(shelf.x() >> 4, shelf.z() >> 4)) {
            return;
        }
        spawnDisplay(shelf);
    }

    /**
     * 移除指定货架的展示实体。
     *
     * <p>按 UUID 取实体并移除；异常安全（区块未加载等场景下静默处理）。
     * 无论实体是否存在，都会从内存索引移除条目。</p>
     *
     * @param shelfId 货架 ID
     */
    public void removeDisplay(String shelfId) {
        DisplayHandle handle = handlesByShelfId.remove(shelfId);
        if (handle == null) {
            return;
        }
        removeEntitySafely(handle.itemDisplayUuid());
    }

    /**
     * 移除指定商店下所有货架的展示。
     *
     * @param shopId 商店 ID
     */
    public void removeShelvesByShop(String shopId) {
        for (Shelf shelf : shopManager.getShelvesByShop(shopId)) {
            removeDisplay(shelf.id());
        }
    }

    /**
     * 区块加载时补齐展示。
     *
     * <p>流程：先清理该 chunk 内所有带 tag 的展示实体（崩溃残留 + 持久化实体，统一重建），
     * 然后对本 chunk 内的货架调用 {@link #refreshShelf}（幂等：清除陈旧 Map 条目后按合格判定重建）。
     * 这样无论实体是否随区块持久化，都能保证最终状态与货架当前数据一致。</p>
     *
     * @param chunk 加载的区块
     */
    public void onChunkLoad(Chunk chunk) {
        // 清理 chunk 内所有展示实体（崩溃残留 + 持久化实体，统一重建）
        // Folia 上 entity.remove 必须在实体所属区域线程执行，用 execute 路由
        for (Entity entity : chunk.getEntities()) {
            if (entity.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                SchedulerUtil.execute(entity, entity::remove);
            }
        }

        String worldName = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // 刷新本 chunk 内所有货架（refreshShelf 幂等：removeDisplay 清陈旧条目，再按合格判定 spawn）
        for (Shelf shelf : shopManager.getAllShelves()) {
            if (!worldName.equals(shelf.world())) {
                continue;
            }
            if (shelf.x() >> 4 != chunkX || shelf.z() >> 4 != chunkZ) {
                continue;
            }
            refreshShelf(shelf);
        }
    }

    /**
     * 移除所有展示实体并清空内存索引（onDisable 调用）。
     */
    public void clearAll() {
        for (DisplayHandle handle : handlesByShelfId.values()) {
            removeEntitySafely(handle.itemDisplayUuid());
        }
        handlesByShelfId.clear();
    }

    // ===== 内部方法 =====

    /**
     * 货架是否具备展示条件：已启用且物品非空非空气。
     *
     * @param shelf 货架
     * @return 满足条件返回 true
     */
    private boolean qualifies(Shelf shelf) {
        return shelf.enabled()
                && shelf.itemStack() != null
                && !shelf.itemStack().getType().isAir();
    }

    /**
     * 为展示实体打上 scoreboard tag、写入 shelfId PDC、标记非持久化。
     *
     * <p>非持久化确保实体不随区块存盘，卸载插件后无磁盘残留；
     * 重建由 {@link #init} 与 {@link #onChunkLoad} 负责。</p>
     *
     * @param entity  展示实体
     * @param shelfId 货架 ID
     */
    private void markDisplayEntity(Entity entity, String shelfId) {
        entity.addScoreboardTag(SCOREBOARD_TAG);
        entity.getPersistentDataContainer().set(shelfIdKey, PersistentDataType.STRING, shelfId);
        entity.setPersistent(false);
    }

    /**
     * 按 UUID 移除实体，异常安全。
     *
     * @param uuid 实体 UUID
     */
    private void removeEntitySafely(UUID uuid) {
        try {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && entity.isValid()) {
                // Folia 上 entity.remove 必须在实体所属区域线程执行，用 execute 路由
                SchedulerUtil.execute(entity, entity::remove);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(() ->
                    "移除展示实体失败 uuid=" + uuid + "：" + e.getMessage());
        }
    }
}
