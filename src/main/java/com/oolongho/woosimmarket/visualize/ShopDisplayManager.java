package com.oolongho.woosimmarket.visualize;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收银台全息展示管理器。
 *
 * <p>在收银台方块上方生成 {@link ItemDisplay}（店主头颅）与 {@link TextDisplay}（店名），
 * 由事件驱动刷新。所有展示实体携带 scoreboard tag {@value #SCOREBOARD_TAG}
 * 与 PDC 键 {@code shopId}，便于崩溃残留清理与溯源。</p>
 *
 * <p>线程模型：所有方法必须在主线程调用（实体生成/移除为主线程操作）。
 * 内存索引使用 {@link ConcurrentHashMap} 保证可见性。</p>
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #init} —— onEnable 调用，清理残留并为已加载区块生成展示</li>
 *   <li>{@link #onChunkLoad} —— 由 ChunkLoadEvent 触发，补齐未加载区块的展示</li>
 *   <li>{@link #refreshShop} —— 由商店变更事件触发，幂等刷新</li>
 *   <li>{@link #clearAll} —— onDisable 调用，移除所有展示实体</li>
 * </ul></p>
 *
 * <p>镜像 {@link ShelfDisplayManager} 模式：scoreboard tag + setPersistent(false) +
 * PDC + 异常安全回收。区别在于一个商店对应一组展示（头颅 + 文本），
 * 句柄由 {@link ShopDisplayHandle} 双 UUID 组成。</p>
 *
 * @author oolongho
 */
public class ShopDisplayManager {

    /** Scoreboard tag，标记所有由本管理器生成的展示实体。 */
    private static final String SCOREBOARD_TAG = "woosimmarket_shop_display";

    /** 头颅展示实体统一缩放比例。 */
    private static final float DISPLAY_SCALE = 0.5f;

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final ConfigLoader configLoader;
    private final Messages messages;
    private final NamespacedKey shopIdKey;

    /** shopId → 展示句柄（ItemDisplay 与 TextDisplay 的 UUID）。 */
    private final Map<String, ShopDisplayHandle> handlesByShopId = new ConcurrentHashMap<>();

    /** 展示句柄：头颅 ItemDisplay 与店名 TextDisplay 的 UUID。 */
    private record ShopDisplayHandle(UUID itemDisplayUuid, UUID textDisplayUuid) {}

    public ShopDisplayManager(WooSimMarket plugin, ShopManager shopManager,
                              ConfigLoader configLoader, Messages messages) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.configLoader = configLoader;
        this.messages = messages;
        this.shopIdKey = new NamespacedKey(plugin, "shopId");
    }

    /**
     * 插件 onEnable 调用：清理所有已加载世界中的残留展示实体，
     * 然后为已加载区块内的商店生成展示。
     *
     * <p>若 {@link ConfigLoader#isShopDisplayEnabled()} 为 false，仅执行清理不生成。</p>
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
        handlesByShopId.clear();

        // 功能禁用时仅清理不生成
        if (!configLoader.isShopDisplayEnabled()) {
            return;
        }

        // 为已加载区块内的商店生成展示（区块未加载时跳过，等 ChunkLoadEvent 补齐）
        for (Shop shop : shopManager.getAllShops()) {
            World world = Bukkit.getWorld(shop.world());
            if (world == null || !world.isChunkLoaded(shop.x() >> 4, shop.z() >> 4)) {
                continue;
            }
            spawnDisplay(shop);
        }
    }

    /**
     * 在收银台方块上方生成头颅 ItemDisplay 与店名 TextDisplay。
     *
     * <p>调用方应已校验 {@link ConfigLoader#isShopDisplayEnabled()} 与区块加载状态。
     * 使用 {@link World#spawn(Location, Class, java.util.function.Consumer)}
     * 在实体加入世界前完成全部配置。</p>
     *
     * @param shop 商店
     */
    public void spawnDisplay(Shop shop) {
        if (!configLoader.isShopDisplayEnabled()) {
            return;
        }
        World world = Bukkit.getWorld(shop.world());
        if (world == null) {
            return;
        }

        // ItemDisplay：店主头颅，billboard 视角跟随玩家
        Location headLoc = new Location(world,
                shop.x() + 0.5, shop.y() + configLoader.getShopDisplayHeadYOffset(), shop.z() + 0.5);
        // TextDisplay：店名（取自 shop.name），billboard 视角跟随玩家
        Location nameLoc = new Location(world,
                shop.x() + 0.5, shop.y() + configLoader.getShopDisplayNameYOffset(), shop.z() + 0.5);

        // Folia 上 world.spawn 必须在 spawn 位置所属区域线程执行，用 runTaskAt 路由；
        // headLoc 与 nameLoc 同 x,z 属同一区域，路由到 headLoc 即可
        SchedulerUtil.runTaskAt(headLoc, () -> {
            ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta headMeta = headItem.getItemMeta();
            if (headMeta instanceof SkullMeta skullMeta) {
                skullMeta.setPlayerProfile(Bukkit.createProfile(shop.ownerUuid()));
                headItem.setItemMeta(skullMeta);
            }
            ItemDisplay itemDisplay = world.spawn(headLoc, ItemDisplay.class, entity -> {
                // VERTICAL：仅水平旋转朝向玩家，垂直方向固定（头颅不上下俯仰）
                entity.setBillboard(Display.Billboard.VERTICAL);
                entity.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf().rotateY((float) Math.PI),
                        new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                        new Quaternionf()));
                entity.setItemStack(headItem);
                markDisplayEntity(entity, shop.id());
            });
            TextDisplay textDisplay = world.spawn(nameLoc, TextDisplay.class, entity -> {
                // VERTICAL：仅水平旋转朝向玩家，垂直方向固定（店名不上下俯仰）
                entity.setBillboard(Display.Billboard.VERTICAL);
                entity.text(buildShopNameComponent(shop));
                markDisplayEntity(entity, shop.id());
            });
            handlesByShopId.put(shop.id(),
                    new ShopDisplayHandle(itemDisplay.getUniqueId(), textDisplay.getUniqueId()));
        });
    }

    /**
     * 原地更新店名 TextDisplay 文本（改名时调用，避免 remove+spawn 闪烁）。
     *
     * <p>仅在展示已存在且实体在线时更新；展示不存在或区块未加载时静默跳过
     * （下次 {@link #onChunkLoad} 或 {@link #refreshShop} 会用新名字重建）。</p>
     *
     * <p>线程模型：本方法可由任意线程调用（如 ChatListener 通过玩家 scheduler 调度）。
     * 内部通过 {@link SchedulerUtil#runTaskAt} 将 TextDisplay 实体操作路由到 shop 所在
     * 区域线程，避免玩家与 shop 跨区域时实体操作跨区域。</p>
     *
     * @param shop 商店（读取 shop.name 作为新文本）
     */
    public void updateShopName(Shop shop) {
        ShopDisplayHandle handle = handlesByShopId.get(shop.id());
        if (handle == null) {
            return;
        }
        World world = Bukkit.getWorld(shop.world());
        if (world == null) {
            return;
        }
        Location shopLoc = new Location(world, shop.x() + 0.5, shop.y(), shop.z() + 0.5);
        // 调度到 shop 所在区域线程：TextDisplay.text() 修改实体 metadata，
        // Folia 上必须在实体所属区域线程执行
        SchedulerUtil.runTaskAt(shopLoc, () -> {
            try {
                Entity entity = Bukkit.getEntity(handle.textDisplayUuid());
                if (entity instanceof TextDisplay textDisplay && entity.isValid()) {
                    textDisplay.text(buildShopNameComponent(shop));
                }
            } catch (Exception e) {
                plugin.getLogger().warning(() ->
                        "更新店名展示失败 shopId=" + shop.id() + "：" + e.getMessage());
            }
        });
    }

    /**
     * 构建店名 Component：取 shop.name，颜色由配置控制（Component.text 不解析 MiniMessage 标签，安全）。
     *
     * @param shop 商店
     * @return 店名 Component
     */
    private Component buildShopNameComponent(Shop shop) {
        Component nameComponent = Component.text(shop.name());
        String textColorHex = configLoader.getShopDisplayTextColor();
        TextColor textColor = TextColor.fromHexString(textColorHex);
        if (textColor != null) {
            nameComponent = nameComponent.color(textColor);
        }
        return nameComponent;
    }

    /**
     * 刷新商店展示（幂等，事件驱动入口）。
     *
     * <p>先移除现有展示，再根据配置与区块加载状态决定是否重新生成。
     * 区块未加载时仅移除不生成（等 ChunkLoadEvent 补齐）。</p>
     *
     * @param shop 商店
     */
    public void refreshShop(Shop shop) {
        removeDisplay(shop.id());
        if (!configLoader.isShopDisplayEnabled()) {
            return;
        }
        World world = Bukkit.getWorld(shop.world());
        if (world == null || !world.isChunkLoaded(shop.x() >> 4, shop.z() >> 4)) {
            return;
        }
        spawnDisplay(shop);
    }

    /**
     * 移除指定商店的展示实体（头颅 + 店名）。
     *
     * <p>按 UUID 取实体并移除；异常安全（区块未加载等场景下静默处理）。
     * 无论实体是否存在，都会从内存索引移除条目。</p>
     *
     * @param shopId 商店 ID
     */
    public void removeDisplay(String shopId) {
        ShopDisplayHandle handle = handlesByShopId.remove(shopId);
        if (handle == null) {
            return;
        }
        removeEntitySafely(handle.itemDisplayUuid());
        removeEntitySafely(handle.textDisplayUuid());
    }

    /**
     * 移除指定商店的展示（别名，与 {@link ShelfDisplayManager#removeShelvesByShop} 命名风格对应）。
     *
     * <p>一个商店仅对应一组展示，直接委托 {@link #removeDisplay}。</p>
     *
     * @param shopId 商店 ID
     */
    public void removeDisplayByShop(String shopId) {
        removeDisplay(shopId);
    }

    /**
     * 区块加载时补齐展示。
     *
     * <p>流程：先清理该 chunk 内所有带 tag 的展示实体（崩溃残留 + 持久化实体，统一重建），
     * 然后对本 chunk 内的商店调用 {@link #refreshShop}（幂等：清除陈旧 Map 条目后重建）。
     * 这样无论实体是否随区块持久化，都能保证最终状态与商店当前数据一致。</p>
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

        // 刷新本 chunk 内所有商店（refreshShop 幂等：removeDisplay 清陈旧条目，再按状态重建）
        for (Shop shop : shopManager.getAllShops()) {
            if (!worldName.equals(shop.world())) {
                continue;
            }
            if (shop.x() >> 4 != chunkX || shop.z() >> 4 != chunkZ) {
                continue;
            }
            refreshShop(shop);
        }
    }

    /**
     * 移除所有展示实体并清空内存索引（onDisable 调用）。
     */
    public void clearAll() {
        for (ShopDisplayHandle handle : handlesByShopId.values()) {
            removeEntitySafely(handle.itemDisplayUuid());
            removeEntitySafely(handle.textDisplayUuid());
        }
        handlesByShopId.clear();
    }

    // ===== 内部方法 =====

    /**
     * 为展示实体打上 scoreboard tag、写入 shopId PDC、标记非持久化。
     *
     * <p>非持久化确保实体不随区块存盘，卸载插件后无磁盘残留；
     * 重建由 {@link #init} 与 {@link #onChunkLoad} 负责。</p>
     *
     * @param entity 展示实体
     * @param shopId 商店 ID
     */
    private void markDisplayEntity(Entity entity, String shopId) {
        entity.addScoreboardTag(SCOREBOARD_TAG);
        entity.getPersistentDataContainer().set(shopIdKey, PersistentDataType.STRING, shopId);
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
