package com.oolonghoo.woosimmarket.npc;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.config.ConfigLoader;
import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.market.MarketManager;
import com.oolonghoo.woosimmarket.model.Shelf;
import com.oolonghoo.woosimmarket.model.Shop;
import com.oolonghoo.woosimmarket.shop.ShopManager;
import com.oolonghoo.woosimmarket.util.TaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPC 管理器 —— 定时刷新、tick 更新、购买判定、销毁。
 *
 * <p>线程模型：所有方法在主线程执行（tick 任务和 spawn 任务均为主线程调度）。
 * 内存 Map 使用 {@link ConcurrentHashMap} 保证可见性。</p>
 *
 * <p>生成策略：定时任务每隔 {@code [spawnIntervalMin, spawnIntervalMax]} 秒触发，
 * 遍历所有商店，未达并发上限则生成 1 个 NPC，随机选择货架作为目标。</p>
 *
 * <p>购买判定：NPC 到达货架后按 {@code P = (BasePrice / UserPrice)^sensitivity × GlobalMult}
 * 概率判定。命中则扣库存、加 balance、记录市场购买、NPC 离开；未命中 NPC 直接离开。</p>
 *
 * <p>BasePrice 由 {@link MarketManager#getFinalBase} 动态计算（供需滑动窗口调价）。</p>
 *
 * @author oolongho
 */
public class NpcManager {

    /** NPC 移动速度（方块/tick，约 5 方块/秒）。 */
    private static final double NPC_SPEED = 0.25;

    /** 视距广播半径（方块）。 */
    private static final double BROADCAST_RADIUS = 48.0;

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final NpcPacketSender packetSender;
    private final ConfigLoader configLoader;
    private final Messages messages;
    private final NpcSkinCache skinCache;
    private final MarketManager marketManager;

    private final Map<UUID, SimNpc> npcsById = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> npcsByShop = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private BukkitTask spawnTask;

    public NpcManager(WooSimMarket plugin, ShopManager shopManager,
                      NpcPacketSender packetSender, ConfigLoader configLoader,
                      Messages messages, NpcSkinCache skinCache,
                      MarketManager marketManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.packetSender = packetSender;
        this.configLoader = configLoader;
        this.messages = messages;
        this.skinCache = skinCache;
        this.marketManager = marketManager;
    }

    /**
     * 启动 NPC 系统：开启 tick 任务和定时生成任务。
     */
    public void start() {
        // 每 tick 更新所有 NPC 位置
        tickTask = TaskUtil.runAtFixed(plugin, this::tick, 0L, 1L);

        // 定时生成 NPC
        scheduleNextSpawn();
    }

    /**
     * 停止 NPC 系统：取消任务、销毁所有 NPC。
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        for (SimNpc npc : new ArrayList<>(npcsById.values())) {
            despawnNpc(npc);
        }
        npcsById.clear();
        npcsByShop.clear();
        packetSender.clearAllCache();
    }

    /**
     * 每 tick 更新所有 NPC 的位置并发包。
     */
    private void tick() {
        if (npcsById.isEmpty()) {
            return;
        }

        for (SimNpc npc : new ArrayList<>(npcsById.values())) {
            SimNpc.TickResult result = npc.tick();

            switch (result) {
                case MOVING -> packetSender.moveToNearby(npc, BROADCAST_RADIUS);
                case REACHED -> handleReached(npc);
                case STUCK -> {
                    plugin.getLogger().info(() -> "NPC " + npc.name() + " 卡住，销毁");
                    despawnNpc(npc);
                }
                case DESPAWN -> despawnNpc(npc);
                case IDLE -> { /* 空闲状态不处理 */ }
            }
        }
    }

    /**
     * NPC 到达货架：执行购买判定，然后让 NPC 离开。
     *
     * @param npc 到达的 NPC
     */
    private void handleReached(SimNpc npc) {
        Shelf shelf = shopManager.getShelf(npc.shelfId());
        if (shelf == null || !shelf.canSell()) {
            // 货架不存在或无可售商品，直接离开
            startLeaving(npc);
            return;
        }

        // 购买概率判定（BasePrice 由 MarketManager 动态计算）
        String itemId = getItemName(shelf.itemStack());
        double userPrice = shelf.price();
        double probability = calculateBuyProbability(itemId, userPrice);
        double roll = ThreadLocalRandom.current().nextDouble();

        if (roll < probability) {
            // 购买成功
            handlePurchase(npc, shelf, itemId);
        }

        // 无论购买与否，NPC 离开
        startLeaving(npc);
    }

    /**
     * 执行购买：扣库存、加商店余额、记录市场购买、广播消息。
     *
     * @param npc    NPC
     * @param shelf  货架
     * @param itemId 物品 ID（Material 枚举名）
     */
    private void handlePurchase(SimNpc npc, Shelf shelf, String itemId) {
        int purchased = shelf.deductStock(1);
        if (purchased <= 0) {
            return;
        }

        // 加商店余额
        double revenue = shelf.price() * purchased;
        Shop shop = shopManager.getShop(npc.shopId());
        if (shop != null) {
            shopManager.addBalance(shop, revenue);
            // 累计销售次数（用于 PlaceholderAPI 占位符）
            shopManager.recordSale(shop.ownerUuid());
        }

        // 持久化货架
        shopManager.saveShelf(shelf);

        // 记录市场购买（动态调价依据）
        marketManager.recordPurchase(itemId);

        // 广播购买消息
        String priceStr = String.format("%.2f", revenue);
        for (Player p : getNearbyPlayers(npc.location(), BROADCAST_RADIUS)) {
            messages.send(p, "npc-purchased", "npc", npc.name(), "item", itemId, "price", priceStr);
        }

        if (configLoader.isDebug()) {
            plugin.getLogger().info(() -> String.format(
                    "NPC %s 购买了 %s x%d，单价 %.2f", npc.name(), itemId, purchased, shelf.price()));
        }
    }

    /**
     * 计算购买概率。
     *
     * <p>公式：{@code P = (BasePrice / UserPrice)^sensitivity × GlobalMult}<br>
     * BasePrice 由 {@link MarketManager#getFinalBase} 动态计算。</p>
     *
     * @param itemId   物品 ID（Material 枚举名）
     * @param userPrice 玩家设定的价格
     * @return 购买概率 [0, 1]
     */
    private double calculateBuyProbability(String itemId, double userPrice) {
        if (userPrice <= 0 || !Double.isFinite(userPrice)) {
            return 0;
        }
        double basePrice = marketManager.getFinalBase(itemId);
        double sensitivity = configLoader.getMarketSensitivity();
        double globalMult = configLoader.getMarketGlobalMultiplier();

        double ratio = basePrice / userPrice;
        double p = Math.pow(ratio, sensitivity) * globalMult;

        // 钳制 [0, 1]
        return Math.max(0, Math.min(1, p));
    }

    /**
     * 让 NPC 朝远离商店的方向离开。
     *
     * @param npc NPC
     */
    private void startLeaving(SimNpc npc) {
        Shop shop = shopManager.getShop(npc.shopId());
        if (shop == null) {
            despawnNpc(npc);
            return;
        }

        World world = Bukkit.getWorld(shop.world());
        if (world == null) {
            despawnNpc(npc);
            return;
        }

        // 朝远离商店的方向生成离开目标点
        double dx = npc.location().getX() - shop.x();
        double dz = npc.location().getZ() - shop.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            // NPC 正好在商店位置，随机选一个方向离开
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            len = 1.0;
        }

        double despawnDist = configLoader.getNpcDespawnDistance();
        double leaveX = npc.location().getX() + (dx / len) * despawnDist;
        double leaveZ = npc.location().getZ() + (dz / len) * despawnDist;

        Location leaveTarget = new Location(world, leaveX, npc.location().getY(), leaveZ);
        npc.startLeaving(leaveTarget);
    }

    /**
     * 销毁 NPC：向所有曾收到 spawn 包的玩家发送移除包、清理内存索引。
     *
     * @param npc NPC
     */
    private void despawnNpc(SimNpc npc) {
        packetSender.removeFromAllTracked(npc);
        npcsById.remove(npc.uuid());
        removeFromShopIndex(npc.shopId(), npc.uuid());
    }

    /**
     * 定时生成 NPC：遍历所有商店，未达上限则生成。
     */
    private void scheduleSpawn() {
        for (Shop shop : shopManager.getAllShops()) {
            int count = countNpcsByShop(shop.id());
            if (count >= configLoader.getNpcMaxConcurrent()) {
                continue;
            }
            trySpawnNpc(shop);
        }
        scheduleNextSpawn();
    }

    /**
     * 尝试为商店生成 1 个 NPC。
     *
     * @param shop 商店
     */
    private void trySpawnNpc(Shop shop) {
        World world = Bukkit.getWorld(shop.world());
        if (world == null) {
            return;
        }

        // 获取商店关联的货架
        List<Shelf> shelves = shopManager.getShelvesByShop(shop.id());
        if (shelves.isEmpty()) {
            return;
        }

        // 过滤可售货架
        List<Shelf> sellable = new ArrayList<>();
        for (Shelf s : shelves) {
            if (s.canSell()) {
                sellable.add(s);
            }
        }
        if (sellable.isEmpty()) {
            return;
        }

        // 随机选择目标货架
        Shelf target = sellable.get(ThreadLocalRandom.current().nextInt(sellable.size()));
        Location targetLoc = new Location(world, target.x() + 0.5, target.y(), target.z() + 0.5);

        // 在商店半径外生成刷新点
        Location spawnLoc = generateSpawnLocation(shop, world);

        // 创建 NPC，皮肤从缓存获取（未命中时 null → Steve 兜底）
        String name = pickName();
        SimNpc.SkinData skin = skinCache.getSkin(name);
        SimNpc npc = new SimNpc(
                UUID.randomUUID(), name, skin,
                shop.id(), target.id(),
                spawnLoc, targetLoc,
                NPC_SPEED,
                configLoader.getNpcTargetReachDistance(),
                configLoader.getNpcStuckThresholdSeconds(),
                configLoader.getNpcStuckThresholdDistance());

        // 发送生成包
        packetSender.spawnToNearby(npc, BROADCAST_RADIUS);

        // 注册到内存索引
        npcsById.put(npc.uuid(), npc);
        npcsByShop.computeIfAbsent(shop.id(), k -> new ArrayList<>()).add(npc.uuid());

        if (configLoader.isDebug()) {
            plugin.getLogger().info(() -> String.format(
                    "生成 NPC %s → 商店 %s → 货架 %s", name, shop.id(), target.id()));
        }
    }

    /**
     * 在商店半径外生成刷新点（地面高度）。
     *
     * @param shop  商店
     * @param world 世界（调用方保证非 null）
     * @return 刷新点
     */
    private Location generateSpawnLocation(Shop shop, World world) {
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double radius = configLoader.getNpcDespawnDistance();
        double x = shop.x() + 0.5 + Math.cos(angle) * radius;
        double z = shop.z() + 0.5 + Math.sin(angle) * radius;
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        // 找到地面高度（从上往下找到第一个非空气方块的上方）
        int y = world.getHighestBlockYAt(blockX, blockZ) + 1;
        if (y < world.getMinHeight()) {
            y = world.getMinHeight();
        }

        return new Location(world, x, y, z);
    }

    /**
     * 从配置的皮肤名列表中随机选一个。
     *
     * @return 玩家名
     */
    private String pickName() {
        List<String> names = configLoader.getSkinNames();
        if (names.isEmpty()) {
            return "NPC";
        }
        return names.get(ThreadLocalRandom.current().nextInt(names.size()));
    }

    /**
     * 安排下一次生成任务。
     */
    private void scheduleNextSpawn() {
        int minSec = configLoader.getNpcSpawnIntervalMin();
        int maxSec = configLoader.getNpcSpawnIntervalMax();
        int delaySec = ThreadLocalRandom.current().nextInt(minSec, maxSec + 1);
        long delayTicks = delaySec * 20L;
        spawnTask = TaskUtil.runLater(plugin, this::scheduleSpawn, delayTicks);
    }

    // ===== 辅助方法 =====

    private int countNpcsByShop(String shopId) {
        List<UUID> list = npcsByShop.get(shopId);
        return list == null ? 0 : list.size();
    }

    private void removeFromShopIndex(String shopId, UUID npcUuid) {
        List<UUID> list = npcsByShop.get(shopId);
        if (list != null) {
            list.remove(npcUuid);
            if (list.isEmpty()) {
                npcsByShop.remove(shopId);
            }
        }
    }

    private String getItemName(ItemStack item) {
        if (item == null) {
            return "Unknown";
        }
        Material mat = item.getType();
        return mat.isItem() ? mat.name() : "Unknown";
    }

    private List<Player> getNearbyPlayers(Location loc, double radius) {
        if (loc.getWorld() == null) {
            return List.of();
        }
        return new ArrayList<>(loc.getWorld().getNearbyPlayers(loc, radius));
    }

    // ===== 公共 API =====

    /**
     * 获取当前 NPC 总数。
     *
     * @return NPC 数量
     */
    public int getNpcCount() {
        return npcsById.size();
    }

    /**
     * 获取指定商店的 NPC 数量。
     *
     * @param shopId 商店 ID
     * @return NPC 数量
     */
    public int getNpcCountByShop(String shopId) {
        return countNpcsByShop(shopId);
    }
}
