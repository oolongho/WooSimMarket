package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.database.DatabaseManager.PurchaseLogRecord;
import com.oolongho.woosimmarket.database.PurchaseLogDao;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.util.TaskUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店统计面板 GUI（InventoryHolder 模式，54 格）。
 *
 * <p>布局（9×6）：
 * <ul>
 *   <li>slot 0：返回按钮（ARROW，回到商店面板）</li>
 *   <li>slot 10-16：总览卡片 7 张（PAPER/GOLD_INGOT/EMERALD/REDSTONE/REPEATER/
 *       DIAMOND/BOOK，对应总判定/总收入/成交/拒绝/购买率/平均成交价/统计窗口）</li>
 *   <li>slot 28-43：物品明细（前 16 个 ItemStat，图标用
 *       {@link Material#matchMaterial}，失败兜底 PAPER）</li>
 *   <li>slot 49：刷新按钮（CLOCK，重新异步查询并刷新）</li>
 *   <li>slot 22：无记录时的空状态指示（PAPER）</li>
 *   <li>其余：LIME_STAINED_GLASS_PANE 边框</li>
 * </ul></p>
 *
 * <p>线程模型：构造时先同步填充边框与按钮（玩家立即看到框架），再通过
 * {@link TaskUtil#runAsync} 查询 purchase_log，{@link TaskUtil#run} 回主线程渲染聚合结果。
 * {@link #refresh()} 复用同一异步→主线程链路，重置内容槽后重新填充。</p>
 *
 * <p>通过 {@link InventoryHolder} 标识 GUI，{@link StatsGuiListener} 据此判断事件归属。</p>
 *
 * @author oolongho
 */
public class StatsGui implements InventoryHolder {

    /** GUI 大小（9×6）。 */
    public static final int SIZE = 54;
    /** 返回按钮槽位（Listener 据此切回 ShopGui）。 */
    public static final int SLOT_BACK = 0;
    /** 刷新按钮槽位（Listener 据此触发 refresh）。 */
    public static final int SLOT_REFRESH = 49;

    /** 无记录时的空状态指示槽位（位于分隔行中央，常态为边框）。 */
    private static final int SLOT_EMPTY = 22;
    /** 总览卡片槽位（顺序对应 PAPER/GOLD_INGOT/EMERALD/REDSTONE/REPEATER/DIAMOND/BOOK）。 */
    private static final int[] OVERVIEW_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    /** 物品明细槽位（最多 16 个，不足留空）。 */
    private static final int[] ITEM_SLOTS = {
            28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43
    };
    /** 所有内容槽位（含空状态槽），刷新时先重置为边框再填充。 */
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16, SLOT_EMPTY,
            28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43
    };

    private final Shop shop;
    private final PurchaseLogDao dao;
    private final Messages messages;
    private final WooSimMarket plugin;
    private final Inventory inventory;

    public StatsGui(Shop shop, PurchaseLogDao dao, Messages messages, WooSimMarket plugin) {
        this.shop = shop;
        this.dao = dao;
        this.messages = messages;
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get("gui-stats-title"));
        fillFrame();
        loadAndRender();
    }

    /**
     * 打开 GUI。
     *
     * @param player 玩家
     */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    /**
     * 重新异步查询并刷新内容（保留边框与按钮，重置内容槽后重新渲染）。
     */
    public void refresh() {
        loadAndRender();
    }

    /**
     * @return 此 GUI 绑定的商店（Listener 返回 ShopGui 时使用）
     */
    public Shop getShop() {
        return shop;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ===== 异步加载与渲染 =====

    /**
     * 异步查询近 {@code stats.retention-days} 天的购买日志，主线程渲染聚合结果。
     * 查询上限取 {@code stats.query-limit}（{@link com.oolongho.woosimmarket.config.ConfigLoader#getStatsQueryLimit}）
     * 作为 LIMIT 保护，防止极端数据量。
     */
    private void loadAndRender() {
        TaskUtil.runAsync(plugin, () -> {
            long sinceMillis = System.currentTimeMillis()
                    - plugin.getConfigLoader().getStatsRetentionDays() * 86400000L;
            List<PurchaseLogRecord> records = dao.findRecentByShopSince(
                    shop.id(), sinceMillis, plugin.getConfigLoader().getStatsQueryLimit());
            TaskUtil.run(plugin, () -> renderContents(records));
        });
    }

    /**
     * 同步填充边框与按钮（构造时调用，玩家立即看到框架，避免异步查询期间的空白闪烁）。
     */
    private void fillFrame() {
        ItemStack border = createItem(Material.LIME_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, border);
        }
        inventory.setItem(SLOT_BACK, createBackButton());
        inventory.setItem(SLOT_REFRESH, createRefreshButton());
    }

    /**
     * 主线程渲染聚合内容：先将内容槽重置为边框（清除刷新前的旧数据），
     * 再按是否有记录填充卡片/明细或空状态。
     *
     * @param records 近期购买日志（异步查询结果）
     */
    private void renderContents(List<PurchaseLogRecord> records) {
        ItemStack border = createItem(Material.LIME_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot : CONTENT_SLOTS) {
            inventory.setItem(slot, border);
        }
        if (records.isEmpty()) {
            inventory.setItem(SLOT_EMPTY, createEmptyIndicator());
            return;
        }
        render(aggregate(records));
    }

    /**
     * 填充总览卡片与物品明细（边框与按钮由 {@link #fillFrame} 维护，不在此处理）。
     *
     * @param stats 聚合统计
     */
    private void render(ShopStats stats) {
        renderOverviewCards(stats);
        renderItemDetails(stats);
    }

    // ===== 聚合 =====

    /**
     * 将购买日志聚合为商店统计快照。
     *
     * <p>perItem 按 attempts 降序、itemId 升序（保证并列时取前 16 的确定性）取前 16。</p>
     *
     * @param records 近期购买日志
     * @return 聚合结果
     */
    private ShopStats aggregate(List<PurchaseLogRecord> records) {
        int totalAttempts = records.size();
        int boughtCount = 0;
        int rejectedCount = 0;
        double totalRevenue = 0;
        Map<String, ItemAccum> accums = new HashMap<>();

        for (PurchaseLogRecord r : records) {
            boolean bought = r.bought();
            if (bought) {
                boughtCount++;
                totalRevenue += r.price();
            } else {
                rejectedCount++;
            }
            ItemAccum a = accums.computeIfAbsent(r.itemId(), ItemAccum::new);
            a.attempts++;
            if (bought) {
                a.bought++;
                a.revenue += r.price();
            } else {
                a.rejected++;
            }
        }

        double purchaseRate = totalAttempts == 0 ? 0.0 : boughtCount * 100.0 / totalAttempts;
        double avgBoughtPrice = boughtCount == 0 ? 0.0 : totalRevenue / boughtCount;

        List<ItemStat> perItem = accums.values().stream()
                .map(ItemAccum::toStat)
                .sorted(Comparator.comparingInt(ItemStat::attempts).reversed()
                        .thenComparing(ItemStat::itemId))
                .limit(16)
                .toList();

        return new ShopStats(totalAttempts, boughtCount, rejectedCount, purchaseRate,
                totalRevenue, avgBoughtPrice, perItem);
    }

    /**
     * 渲染 7 张总览卡片（总判定/总收入/成交/拒绝/购买率/平均成交价/统计窗口）。
     */
    private void renderOverviewCards(ShopStats stats) {
        int retentionDays = plugin.getConfigLoader().getStatsRetentionDays();
        inventory.setItem(OVERVIEW_SLOTS[0], createCard(Material.PAPER,
                "gui-stats-total-attempts", "gui-stats-total-attempts-lore",
                "value", String.valueOf(stats.totalAttempts())));
        inventory.setItem(OVERVIEW_SLOTS[1], createCard(Material.GOLD_INGOT,
                "gui-stats-revenue", "gui-stats-revenue-lore",
                "value", plugin.getEconomyManager().format(stats.totalRevenue())));
        inventory.setItem(OVERVIEW_SLOTS[2], createCard(Material.EMERALD,
                "gui-stats-bought", "gui-stats-bought-lore",
                "value", String.valueOf(stats.boughtCount())));
        inventory.setItem(OVERVIEW_SLOTS[3], createCard(Material.REDSTONE,
                "gui-stats-rejected", "gui-stats-rejected-lore",
                "value", String.valueOf(stats.rejectedCount())));
        inventory.setItem(OVERVIEW_SLOTS[4], createCard(Material.REPEATER,
                "gui-stats-rate", "gui-stats-rate-lore",
                "value", String.format("%.1f", stats.purchaseRate())));
        inventory.setItem(OVERVIEW_SLOTS[5], createCard(Material.DIAMOND,
                "gui-stats-avg-price", "gui-stats-avg-price-lore",
                "value", plugin.getEconomyManager().format(stats.avgBoughtPrice())));
        inventory.setItem(OVERVIEW_SLOTS[6], createCard(Material.BOOK,
                "gui-stats-window", "gui-stats-window-lore",
                "days", String.valueOf(retentionDays)));
    }

    /**
     * 渲染物品明细（前 16 个 ItemStat，不足 16 个时剩余槽位保持边框）。
     */
    private void renderItemDetails(ShopStats stats) {
        List<ItemStat> items = stats.perItem();
        int count = Math.min(ITEM_SLOTS.length, items.size());
        for (int i = 0; i < count; i++) {
            inventory.setItem(ITEM_SLOTS[i], createItemDetail(items.get(i)));
        }
    }

    // ===== 物品创建 =====

    /**
     * 创建带名称与 lore 的物品（meta 为 null 时返回未修改的原物品）。
     *
     * @param material 图标材质
     * @param name     显示名称（已解析的 Component）
     * @param lore     lore 行列表（不可为 null）
     * @return 物品
     */
    private ItemStack createItem(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack createBackButton() {
        return createItem(Material.ARROW,
                messages.get("gui-stats-back"),
                List.of(messages.get("gui-stats-back-lore")));
    }

    private ItemStack createRefreshButton() {
        return createItem(Material.CLOCK,
                messages.get("gui-stats-refresh"),
                List.of());
    }

    private ItemStack createEmptyIndicator() {
        return createItem(Material.PAPER,
                messages.get("gui-stats-empty"),
                List.of());
    }

    /**
     * 创建总览卡片：名称取 nameKey，lore 取 loreKey 并替换占位符。
     *
     * @param material    图标材质
     * @param nameKey     名称 lang 键
     * @param loreKey     lore lang 键（含 {@code {placeholder}}）
     * @param placeholder 占位符名（如 "value" / "limit"）
     * @param value       占位符替换值
     */
    private ItemStack createCard(Material material, String nameKey, String loreKey,
                                  String placeholder, String value) {
        return createItem(material,
                messages.get(nameKey),
                List.of(messages.get(loreKey, placeholder, value)));
    }

    /**
     * 创建物品明细图标：图标用 {@link Material#matchMaterial}，失败兜底 PAPER；
     * displayName 为 itemId，lore 显示判定/成交/收入。
     *
     * @param stat 单物品统计
     * @return 物品
     */
    private ItemStack createItemDetail(ItemStat stat) {
        Material material = Material.matchMaterial(stat.itemId());
        if (material == null) {
            material = Material.PAPER;
        }
        return createItem(material,
                Component.text(stat.itemId()),
                List.of(
                        messages.get("gui-stats-item-attempts", "value", String.valueOf(stat.attempts())),
                        messages.get("gui-stats-item-bought", "value", String.valueOf(stat.bought())),
                        messages.get("gui-stats-item-revenue", "value",
                                plugin.getEconomyManager().format(stat.revenue()))
                ));
    }

    // ===== 数据载体 =====

    /**
     * 商店统计快照。
     *
     * @param totalAttempts  总判定次数
     * @param boughtCount    成交数
     * @param rejectedCount  拒绝数
     * @param purchaseRate   购买率（0-100，totalAttempts=0 时为 0.0）
     * @param totalRevenue   总收入（成交记录的 price 之和）
     * @param avgBoughtPrice 平均成交价（totalRevenue / boughtCount，boughtCount=0 时为 0.0）
     * @param perItem        按判定次数降序的前 16 个物品统计
     */
    public record ShopStats(
            int totalAttempts,
            int boughtCount,
            int rejectedCount,
            double purchaseRate,
            double totalRevenue,
            double avgBoughtPrice,
            List<ItemStat> perItem
    ) {
    }

    /**
     * 单物品统计。
     *
     * @param itemId   物品 ID（Material 名或 namespace:path）
     * @param attempts 判定次数
     * @param bought   成交数
     * @param rejected 拒绝数
     * @param revenue  收入（成交记录的 price 之和）
     * @param avgPrice 平均成交价（revenue / bought，bought=0 时为 0.0）
     */
    public record ItemStat(
            String itemId,
            int attempts,
            int bought,
            int rejected,
            double revenue,
            double avgPrice
    ) {
    }

    /** 物品聚合的可变累加器（聚合过程中使用，完成后转为 {@link ItemStat}）。 */
    private static final class ItemAccum {
        private final String itemId;
        private int attempts;
        private int bought;
        private int rejected;
        private double revenue;

        ItemAccum(String itemId) {
            this.itemId = itemId;
        }

        ItemStat toStat() {
            double avg = bought == 0 ? 0.0 : revenue / bought;
            return new ItemStat(itemId, attempts, bought, rejected, revenue, avg);
        }
    }
}
