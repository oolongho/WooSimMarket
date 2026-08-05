package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.hook.CraftEngineHook;
import com.oolongho.woosimmarket.model.Shelf;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 货架管理 GUI（InventoryHolder 模式）。
 *
 * <p>27 格箱子布局（# 边框 / A 商品槽 / B 价格按钮 / C 开关按钮 / D 标准价表按钮）：
 * <pre>
 * ###AAA###
 * #B#AAA#C#
 * #D#AAA###
 * </pre>
 * <ul>
 *   <li>9 个商品槽 {3,4,5,12,13,14,21,22,23}：玩家放入/取出物品，打开时按 maxStackSize 分配 stock</li>
 *   <li>slot 10：价格按钮（绑定后图标为绑定物品 + 附魔光效，未绑定为 GOLD_INGOT）</li>
 *   <li>slot 16：启用/禁用按钮</li>
 *   <li>slot 19：标准价表按钮（BOOK，查看全物品标准价）</li>
 *   <li>其余：灰色玻璃边框（不可交互）</li>
 * </ul></p>
 *
 * <p>物品绑定：玩家放入第一个物品时按 itemId 绑定（原版 {@link Material#name()} 或 CE namespace:path），
 * 之后仅允许同 itemId 物品放入 9 格。关闭时汇总 9 格 stock；9 格全空则解绑（itemStack=null, stock=0, itemId=null）。</p>
 *
 * <p>价格按钮需 {@link CraftEngineHook} 构造本地化图标与 displayName，由监听器 onOpen 时调用
 * {@link #refresh(Messages, CraftEngineHook)} 注入（构造器不持有 craftEngine）。</p>
 *
 * @author oolongho
 */
public class ShelfGui implements InventoryHolder {

    /** GUI 大小。 */
    public static final int SIZE = 27;
    /** 价格按钮槽位。 */
    public static final int SLOT_PRICE = 10;
    /** 启用/禁用按钮槽位。 */
    public static final int SLOT_TOGGLE = 16;
    /** 标准价表按钮槽位（BOOK，打开 PriceTableGui）。 */
    public static final int SLOT_PRICE_TABLE = 19;
    /** 9 个商品槽（按行序，用于 stock 分配与汇总）。 */
    public static final int[] ITEM_SLOTS = {3, 4, 5, 12, 13, 14, 21, 22, 23};

    private final Shelf shelf;
    private final Inventory inventory;
    /** 打开时按 maxStackSize 分配到 9 格的 stock 快照（onClose delta 计算用，避免覆盖 GUI 开启期间 NPC 购买导致的 shelf.stock 变化）。 */
    private final int distributedAtOpen;

    public ShelfGui(Shelf shelf, Messages messages) {
        this.shelf = shelf;
        Component title = messages.get("gui-shelf-title");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(messages);
        this.distributedAtOpen = collectStock();
    }

    /** @return 打开时 9 格分配的 stock 快照（onClose delta 计算用） */
    public int getDistributedAtOpen() {
        return distributedAtOpen;
    }

    private void render(Messages messages) {
        ItemStack border = createBorder();
        for (int i = 0; i < SIZE; i++) {
            if (!isItemSlot(i) && i != SLOT_PRICE && i != SLOT_TOGGLE && i != SLOT_PRICE_TABLE) {
                inventory.setItem(i, border);
            }
        }
        distributeStock();
        // 价格按钮需 CraftEngineHook 构造本地化图标，由监听器 onOpen 时调用 refresh 注入
        inventory.setItem(SLOT_TOGGLE, createToggleButton(shelf.enabled(), messages));
        inventory.setItem(SLOT_PRICE_TABLE, createPriceTableButton(messages));
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
     * 刷新价格与开关按钮（不清空商品槽，玩家可能已放入物品）。
     *
     * @param messages     消息管理器
     * @param craftEngine   CraftEngine 钩子（用于构造价格按钮图标与 displayName）
     */
    public void refresh(Messages messages, CraftEngineHook craftEngine) {
        inventory.setItem(SLOT_PRICE, createPriceButton(craftEngine, persistentBoundItemId(), shelf.price(), messages));
        inventory.setItem(SLOT_TOGGLE, createToggleButton(shelf.enabled(), messages));
    }

    /**
     * 打开时将 stock 按 maxStackSize 分配到 9 个商品槽（前 N 格满叠 + 1 格余量）。
     */
    private void distributeStock() {
        ItemStack template = shelf.itemStack();
        if (template == null || template.getType().isAir() || shelf.stock() <= 0) {
            return;
        }
        int remaining = shelf.stock();
        int maxStack = template.getMaxStackSize();
        for (int slot : ITEM_SLOTS) {
            if (remaining <= 0) {
                break;
            }
            int amt = Math.min(remaining, maxStack);
            ItemStack display = template.clone();
            display.setAmount(amt);
            inventory.setItem(slot, display);
            remaining -= amt;
        }
    }

    /**
     * 汇总 9 个商品槽的物品总数（关闭时调用，用于持久化 stock）。
     *
     * @return 9 格物品 amount 之和；全空返回 0
     */
    public int collectStock() {
        int total = 0;
        for (int slot : ITEM_SLOTS) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) {
                total += it.getAmount();
            }
        }
        return total;
    }

    /**
     * 取 9 个商品槽中首个非空物品作为模板（clone 后 amount=1）；全空返回 null。
     *
     * @return 模板物品（amount=1）或 null
     */
    public ItemStack collectTemplate() {
        for (int slot : ITEM_SLOTS) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) {
                ItemStack template = it.clone();
                template.setAmount(1);
                return template;
            }
        }
        return null;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Shelf getShelf() {
        return shelf;
    }

    /**
     * 当前活体绑定 itemId（点击/拖拽校验用）：优先取 9 格中首个物品的 itemId，
     * 回落到 shelf 持久化 itemId；均无返回 null。
     *
     * @param craftEngine CraftEngine 钩子（用于计算物品 itemId）
     * @return 绑定 itemId，未绑定返回 null
     */
    public String liveBoundItemId(CraftEngineHook craftEngine) {
        for (int slot : ITEM_SLOTS) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) {
                return craftEngine.getItemId(it);
            }
        }
        return persistentBoundItemId();
    }

    /**
     * 判断槽位是否为商品槽。
     */
    public static boolean isItemSlot(int slot) {
        for (int s : ITEM_SLOTS) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    // ===== 内部 =====

    /** 持久化绑定 itemId（shelf.itemId），用于渲染价格按钮。 */
    private String persistentBoundItemId() {
        return shelf.itemId();
    }

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 价格按钮：绑定后图标为 craftEngine 构造的绑定物品 + 附魔光效，未绑定为 GOLD_INGOT。
     * displayName 绑定时用 {@link CraftEngineHook#displayName(String)}（translatable），
     * 未绑定时用 {@code gui-shelf-set-price}。
     */
    private static ItemStack createPriceButton(CraftEngineHook craftEngine, String itemId, double price, Messages messages) {
        boolean bound = itemId != null;
        ItemStack item = bound ? craftEngine.createItemStack(itemId) : new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(bound ? craftEngine.displayName(itemId) : messages.get("gui-shelf-set-price"));
            meta.lore(List.of(
                    messages.get("gui-shelf-current-price", "price", String.format("%.2f", price)),
                    messages.get(bound ? "gui-shelf-bound" : "gui-shelf-unbound")));
            if (bound) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createToggleButton(boolean enabled, Messages messages) {
        ItemStack item = new ItemStack(enabled ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get(enabled ? "gui-shelf-enabled" : "gui-shelf-disabled"));
            meta.lore(List.of(messages.get("gui-shelf-toggle-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 标准价表按钮：BOOK 图标，点击打开 PriceTableGui（从货架入口时返回 ShelfGui）。
     */
    private static ItemStack createPriceTableButton(Messages messages) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shelf-price-table"));
            meta.lore(List.of(messages.get("gui-shelf-price-table-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }
}
