package com.oolongho.woosimmarket.npc;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.market.MarketManager;
import com.oolongho.woosimmarket.market.PurchaseFormula;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.util.TaskUtil;
import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import com.oolongho.woosimmarket.visualize.ThoughtDisplayManager;
import com.oolongho.woosimmarket.visualize.ThoughtDisplayManager.Phase;
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
 * <p>购买判定：NPC 到达货架后委托 {@link PurchaseFormula} 计算 5 因子概率
 * （price/market/budget/weather/time），结果缓存后进入徘徊状态（DELIBERATING），
 * 由 impatience 决定判定次数与间隔，多次 roll 任一命中即购买；命中或判定耗尽
 * 则离开。P=0（硬不可能买）跳过徘徊直接离开。徘徊期间由
 * {@link ThoughtDisplayManager} 在 NPC 头顶展示性格化思考文本。</p>
 *
 * @author oolongho
 */
public class NpcManager {

    /** 视距广播半径（方块）。 */
    private static final double BROADCAST_RADIUS = 48.0;

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final NpcPacketSender packetSender;
    private final ConfigLoader configLoader;
    private final Messages messages;
    private final NpcSkinCache skinCache;
    private final MarketManager marketManager;
    private final PurchaseFormula purchaseFormula;
    private final ShelfDisplayManager shelfDisplayManager;
    private final ThoughtDisplayManager thoughtDisplayManager;
    private final EquipmentProvider equipmentProvider;
    private final PersonalityManager personalityManager;

    private final Map<UUID, SimNpc> npcsById = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> npcsByShop = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private BukkitTask spawnTask;

    public NpcManager(WooSimMarket plugin, ShopManager shopManager,
                      NpcPacketSender packetSender, ConfigLoader configLoader,
                      Messages messages, NpcSkinCache skinCache,
                      MarketManager marketManager, ShelfDisplayManager shelfDisplayManager,
                      PersonalityManager personalityManager, PurchaseFormula purchaseFormula,
                      ThoughtDisplayManager thoughtDisplayManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.packetSender = packetSender;
        this.configLoader = configLoader;
        this.messages = messages;
        this.skinCache = skinCache;
        this.marketManager = marketManager;
        this.shelfDisplayManager = shelfDisplayManager;
        this.equipmentProvider = new EquipmentProvider(configLoader);
        this.personalityManager = personalityManager;
        this.purchaseFormula = purchaseFormula;
        this.thoughtDisplayManager = thoughtDisplayManager;
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
        thoughtDisplayManager.clearAll();
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
                case REACHED -> {
                    // SWITCHING 状态返回 REACHED 表示换架到达，走专门的恢复流程；
                    // 其他状态（MOVING）返回 REACHED 表示初到货架，走 handleReached。
                    if (npc.state() == SimNpc.State.SWITCHING) {
                        handleSwitchArrival(npc);
                    } else {
                        handleReached(npc);
                    }
                }
                case ROLL_DUE -> handleRoll(npc);
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
     * NPC 到达货架：计算购买概率并进入徘徊判定（子系统 3）。
     *
     * <p>流程：查 shelf → 算 P（{@link PurchaseFormula#calculate} 一次）→
     * P&lt;=0 直接离开（硬不可能买，跳过徘徊）→ 否则按 impatience 计算
     * rolls/interval → 调 {@link SimNpc#startDeliberation}。后续每次 roll 由
     * {@link #handleRoll} 处理。</p>
     *
     * @param npc 到达的 NPC
     */
    private void handleReached(SimNpc npc) {
        Shelf shelf = shopManager.getShelf(npc.currentShelfId());
        if (shelf == null || !shelf.canSell()) {
            // 货架不存在或无可售商品，直接离开
            npc.startLeaving();
            return;
        }

        // 购买概率判别式（委托 PurchaseFormula，计算一次缓存于 SimNpc）
        String itemId = getItemName(shelf.itemStack());
        double userPrice = shelf.price();
        double probability = purchaseFormula.calculate(
                npc.personality(), itemId, userPrice, npc.location().getWorld());

        // P=0 硬不可能买：跳过徘徊直接离开（避免不真实逗留 + 浪费 tick）
        if (probability <= 0.0) {
            npc.startLeaving();
            return;
        }

        // 按 impatience 计算判定次数与间隔（线性 lerp）
        int rolls = computeRolls(npc.personality().impatience());
        int interval = computeInterval(npc.personality().impatience());

        // 进入徘徊状态（首次 roll 即时触发，下一 tick 即 ROLL_DUE）
        npc.startDeliberation(rolls, interval, probability);
        thoughtDisplayManager.spawn(npc, Phase.ENTER);
    }

    /**
     * 执行一次徘徊判定 roll（由 tick() 在 {@link SimNpc.TickResult#ROLL_DUE} 时调用）。
     *
     * <p>流程：重检 {@code shelf.canSell()}（徘徊期间可能被买空/拆除）→
     * 用缓存 P roll → 命中则 {@link #handlePurchase} + {@link SimNpc#startLeaving()}；
     * 未命中且 {@code rollsDone >= totalRolls} 则 startLeaving；未命中且仍有余量
     * 则返回（SimNpc 计时器已由 tick() 重置，自动推进下次 roll）。
     * 命中或判定耗尽时调用 {@link MarketManager#recordPurchaseOutcome} 更新购买动量 EMA。</p>
     *
     * @param npc 处于 DELIBERATING 状态的 NPC
     */
    private void handleRoll(SimNpc npc) {
        Shelf shelf = shopManager.getShelf(npc.currentShelfId());
        if (shelf == null || !shelf.canSell()) {
            // 徘徊期间货架被买空/消失，立即回收展示并离开
            thoughtDisplayManager.despawn(npc);
            npc.startLeaving();
            return;
        }

        String itemId = getItemName(shelf.itemStack());
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < npc.deliberationProbability()) {
            // 命中：购买、flash BUY 文本、离开
            handlePurchase(npc, shelf, itemId);
            marketManager.recordPurchaseOutcome(itemId, true);
            thoughtDisplayManager.flash(npc, Phase.BUY);
            npc.startLeaving();
            return;
        }

        // 未命中：判定耗尽则 flash GIVE_UP 并离开，否则 roll 换架概率
        if (npc.deliberationRollsDone() >= npc.deliberationTotalRolls()) {
            thoughtDisplayManager.flash(npc, Phase.GIVE_UP);
            marketManager.recordPurchaseOutcome(itemId, false);
            npc.startLeaving();
        } else {
            // roll 换架概率：命中则切换到另一可售货架，未命中则切 HESITATE 等待下次 roll
            if (ThreadLocalRandom.current().nextDouble() < configLoader.getShelfSwitchProbability()) {
                switchToRandomShelf(npc);
            } else {
                thoughtDisplayManager.update(npc, Phase.HESITATE);
            }
        }
    }

    /**
     * 尝试切换到商店内另一个可售货架。
     *
     * <p>由 {@link #handleRoll} 在判定未命中且 roll 命中换架概率时调用。
     * 流程：从同商店的可售货架中排除当前货架，随机选一个作为目标；
     * 无其他可售货架时不换，留在原架切 HESITATE。</p>
     *
     * <p>换架前先 {@link ThoughtDisplayManager#despawn} 移除旧货架的思考展示
     * （换架期间 NPC 在移动，不展示思考文本）；到达后由
     * {@link #handleSwitchArrival} 重新 spawn。</p>
     *
     * @param npc 处于 DELIBERATING 状态的 NPC
     */
    private void switchToRandomShelf(SimNpc npc) {
        List<Shelf> shelves = shopManager.getShelvesByShop(npc.shopId());
        List<Shelf> candidates = new ArrayList<>();
        for (Shelf s : shelves) {
            if (s.canSell() && !s.id().equals(npc.currentShelfId())) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            // 无其他可售货架，留原架
            thoughtDisplayManager.update(npc, Phase.HESITATE);
            return;
        }

        Shelf target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        World world = npc.location().getWorld();
        if (world == null) {
            thoughtDisplayManager.update(npc, Phase.HESITATE);
            return;
        }

        Location targetLoc = new Location(world, target.x() + 0.5, target.y(), target.z() + 0.5);
        long deadline = System.currentTimeMillis() + configLoader.getSwitchTimeoutSeconds() * 1000L;

        // 移除旧货架的思考展示（换架期间不展示）
        thoughtDisplayManager.despawn(npc);

        // 切换到新货架（state → SWITCHING）
        npc.switchShelf(target.id(), targetLoc, deadline);

        if (configLoader.isDebugGeneral()) {
            plugin.getLogger().info(() -> String.format(
                    "NPC %s 换架 → 货架 %s (%d,%d,%d)，超时 %ds",
                    npc.name(), target.id(), target.x(), target.y(), target.z(),
                    configLoader.getSwitchTimeoutSeconds()));
        }
    }

    /**
     * 换架到达后处理：重检货架可售性 → 重算 P → 回到 DELIBERATING。
     *
     * <p>由 {@link #tick} 在 SWITCHING 状态返回 REACHED 时调用。流程：
     * <ol>
     *   <li>查 shelf，不可售（被买空/消失）→ startLeaving 离开</li>
     *   <li>重算 P（{@link PurchaseFormula#calculate}）：换架后价格/库存可能变化，
     *       需重算；P&lt;=0 直接离开</li>
     *   <li>更新缓存的 P（{@link SimNpc#updateDeliberationProbability}）</li>
     *   <li>调 {@link SimNpc#resumeDeliberation()} 切回 DELIBERATING（不重置判定次数）</li>
     *   <li>重新 spawn 思考展示（Phase.ENTER，新货架新思考）</li>
     * </ol>
     * </p>
     *
     * <p>注意：判定次数（rollsDone/totalRolls）不重置，NPC 在新货架上继续完成
     * 剩余判定。这样换架是"换一个角度看商品"，而非"重新开始犹豫"。</p>
     *
     * @param npc 处于 SWITCHING 状态、刚到达新货架的 NPC
     */
    private void handleSwitchArrival(SimNpc npc) {
        Shelf shelf = shopManager.getShelf(npc.currentShelfId());
        if (shelf == null || !shelf.canSell()) {
            // 货架被买空/消失，直接离开
            npc.startLeaving();
            return;
        }

        String itemId = getItemName(shelf.itemStack());
        double userPrice = shelf.price();
        double probability = purchaseFormula.calculate(
                npc.personality(), itemId, userPrice, npc.location().getWorld());

        if (probability <= 0.0) {
            npc.startLeaving();
            return;
        }

        // 更新缓存的 P，回到 DELIBERATING（判定次数不重置）
        npc.updateDeliberationProbability(probability);
        npc.resumeDeliberation();
        thoughtDisplayManager.spawn(npc, Phase.ENTER);
    }

    /**
     * 由 impatience 线性映射判定次数。
     *
     * <p>{@code rolls = round(1 + (maxRolls − 1) × (1 − impatience))}，范围 [1, maxRolls]。
     * impatience=1（急躁）→ 1 次；impatience=0（耐心）→ maxRolls 次。
     * impatience 由 {@link PersonalityManager} 加载时钳制到 [0,1]，此处不再重复钳制。</p>
     *
     * @param impatience 性格冲动度 [0,1]
     * @return 判定次数
     */
    private int computeRolls(double impatience) {
        int maxRolls = configLoader.getNpcDeliberationMaxRolls();
        double t = 1.0 - impatience;
        return (int) Math.round(1 + (maxRolls - 1) * t);
    }

    /**
     * 由 impatience 线性映射判定间隔（ticks）。
     *
     * <p>{@code interval = round(intervalMin + (intervalMax − intervalMin) × (1 − impatience))}。
     * impatience=1 → intervalMin（快决）；impatience=0 → intervalMax（慢决）。</p>
     *
     * @param impatience 性格冲动度 [0,1]
     * @return 判定间隔（ticks）
     */
    private int computeInterval(double impatience) {
        int min = configLoader.getNpcDeliberationIntervalMinTicks();
        int max = configLoader.getNpcDeliberationIntervalMaxTicks();
        double t = 1.0 - impatience;
        return (int) Math.round(min + (max - min) * t);
    }

    /**
     * 执行购买：扣库存、加商店余额、广播消息。
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

        // 刷新货架展示（基于最新库存数据）
        shelfDisplayManager.refreshShelf(shelf);

        // 广播购买消息
        String priceStr = String.format("%.2f", revenue);
        for (Player p : getNearbyPlayers(npc.location(), BROADCAST_RADIUS)) {
            messages.send(p, "npc-purchased", "npc", npc.name(), "item", itemId, "price", priceStr);
        }

        if (configLoader.isDebugGeneral()) {
            plugin.getLogger().info(() -> String.format(
                    "NPC %s 购买了 %s x%d，单价 %.2f", npc.name(), itemId, purchased, shelf.price()));
        }
    }

    /**
     * 销毁 NPC：向所有曾收到 spawn 包的玩家发送移除包、清理内存索引。
     *
     * @param npc NPC
     */
    private void despawnNpc(SimNpc npc) {
        thoughtDisplayManager.despawn(npc);
        packetSender.removeFromAllTracked(npc);
        npcsById.remove(npc.uuid());
        removeFromShopIndex(npc.shopId(), npc.uuid());
    }

    /**
     * 定时生成 NPC：遍历所有商店，补满至目标数量。
     *
     * <p>目标数量 = {@code min(max-concurrent, max(1, ceil(启用货架数 × spawn-factor)))}。
     * 通过 while 循环补满，而非单次只生成 1 个，让 NPC 数量随货架数动态伸缩。
     * 防御：若 trySpawnNpc 未成功生成（如货架为空），跳出避免死循环。</p>
     */
    private void scheduleSpawn() {
        for (Shop shop : shopManager.getAllShops()) {
            int current = countNpcsByShop(shop.id());
            // 计算目标数量：min(max-concurrent, 已启用可售货架数 × spawn-factor)
            List<Shelf> shelves = shopManager.getShelvesByShop(shop.id());
            long enabledCount = shelves.stream().filter(Shelf::canSell).count();
            int targetCount = (int) Math.min(
                    configLoader.getNpcMaxConcurrent(),
                    Math.max(1, (int) Math.ceil(enabledCount * configLoader.getNpcSpawnFactor())));
            while (countNpcsByShop(shop.id()) < targetCount) {
                trySpawnNpc(shop);
                // 防御：如果 trySpawnNpc 没有成功生成（如货架为空），跳出
                if (countNpcsByShop(shop.id()) == current) break;
                current = countNpcsByShop(shop.id());
            }
        }
        scheduleNextSpawn();
    }

    /**
     * 尝试为商店生成 1 个 NPC。
     *
     * <p>生命周期：创建 NPC（{@link SimNpc.State#WAITING_FOR_PATH}）→ 注册到索引 →
     * 提交异步 A* 寻路 → 寻路回调中 setPath + spawnToNearby 或 despawnNpc。
     * 此时不发送 spawn 包（路径未就绪）。</p>
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

        // 创建 NPC：初始状态 WAITING_FOR_PATH，不传 target（路径由异步 A* 寻路提供）
        // 性格由 PersonalityManager 加权随机分配（spawn 时确定，不持久化）
        String name = pickName();
        SimNpc.SkinData skin = skinCache.getSkin(name);
        PersonalityProfile personality = personalityManager.random();
        SimNpc npc = new SimNpc(
                UUID.randomUUID(), name, skin, equipmentProvider.random(),
                shop.id(), target.id(), personality,
                spawnLoc,
                configLoader.getNpcSpeed(),
                configLoader.getNpcTargetReachDistance(),
                configLoader.getNpcStuckThresholdSeconds(),
                configLoader.getNpcStuckThresholdDistance());

        // 先注册到内存索引（tick() 处理 WAITING_FOR_PATH 返回 IDLE，不移动不发包）
        npcsById.put(npc.uuid(), npc);
        npcsByShop.computeIfAbsent(shop.id(), k -> new ArrayList<>()).add(npc.uuid());

        if (configLoader.isDebugGeneral()) {
            plugin.getLogger().info(() -> String.format(
                    "生成 NPC %s [性格=%s] → 商店 %s → 货架 %s start=(%d,%d,%d) target=(%d,%d,%d)（等待寻路）",
                    name, personality.name(), shop.id(), target.id(),
                    spawnLoc.getBlockX(), spawnLoc.getBlockY(), spawnLoc.getBlockZ(),
                    target.x(), target.y(), target.z()));
        }

        // 提交异步 A* 寻路（回调在主线程执行，可安全操作 NPC）
        NpcPathfinder.findPath(
                plugin, world, spawnLoc, targetLoc,
                configLoader.getNpcPathfindingMaxDistance(),
                configLoader.getNpcPathfindingMaxIterations(),
                path -> {
                    // 防御：NPC 可能已在 stop() 或其他流程中销毁，避免生成幽灵 NPC
                    if (!npcsById.containsKey(npc.uuid())) {
                        return;
                    }
                    if (path == null || path.isEmpty()) {
                        plugin.getLogger().info(() -> "NPC " + name + " 寻路失败，销毁");
                        despawnNpc(npc);
                        return;
                    }
                    // 路径就绪：setPath 切换到 MOVING 状态，发送生成包
                    npc.setPath(path);
                    packetSender.spawnToNearby(npc, BROADCAST_RADIUS);
                    if (configLoader.isDebugGeneral()) {
                        plugin.getLogger().info(() -> "NPC " + name + " 寻路成功，" + path.size() + " 个路径点");
                    }
                });
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
