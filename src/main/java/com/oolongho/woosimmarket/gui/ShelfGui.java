package com.oolongho.woosimmarket.gui;

import com.oolongho.woosimmarket.config.Messages;
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
 * <p>27 格箱子布局（# 边框 / A 商品槽 / B 价格按钮 / C 开关按钮）：
 * <pre>
 * ###AAA###
 * #B#AAA#C#
 * ###AAA###
 * </pre>
 * <ul>
 *   <li>9 个商品槽 {3,4,5,12,13,14,21,22,23}：玩家放入/取出物品，打开时按 maxStackSize 分配 stock</li>
 *   <li>slot 10：价格按钮（绑定后图标变为绑定物品 Material + 附魔光效，未绑定为 GOLD_INGOT）</li>
 *   <li>slot 16：启用/禁用按钮</li>
 *   <li>其余：灰色玻璃边框（不可交互）</li>
 * </ul></p>
 *
 * <p>物品绑定：玩家放入第一个物品时按 Material 绑定，之后仅允许同种类放入 9 格。
 * 关闭时汇总 9 格 stock；9 格全空则解绑（itemStack=null, stock=0）。</p>
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
    /** 9 个商品槽（按行序，用于 stock 分配与汇总）。 */
    public static final int[] ITEM_SLOTS = {3, 4, 5, 12, 13, 14, 21, 22, 23};

    private final Shelf shelf;
    private final Inventory inventory;

    public ShelfGui(Shelf shelf, Messages messages) {
        this.shelf = shelf;
        Component title = messages.get("gui-shelf-title");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render(messages);
    }

    private void render(Messages messages) {
        ItemStack border = createBorder();
        for (int i = 0; i < SIZE; i++) {
            if (!isItemSlot(i) && i != SLOT_PRICE && i != SLOT_TOGGLE) {
                inventory.setItem(i, border);
            }
        }
        distributeStock();
        inventory.setItem(SLOT_PRICE, createPriceButton(persistentBoundMaterial(), shelf.price(), messages));
        inventory.setItem(SLOT_TOGGLE, createToggleButton(shelf.enabled(), messages));
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
     * @param messages 消息管理器
     */
    public void refresh(Messages messages) {
        inventory.setItem(SLOT_PRICE, createPriceButton(persistentBoundMaterial(), shelf.price(), messages));
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
     * 当前活体绑定材质（点击/拖拽校验用）：优先取 9 格中首个物品，回落到 shelf 持久化物品；均无返回 null。
     *
     * @return 绑定材质，未绑定返回 null
     */
    public Material liveBoundMaterial() {
        for (int slot : ITEM_SLOTS) {
            ItemStack it = inventory.getItem(slot);
            if (it != null && !it.getType().isAir()) {
                return it.getType();
            }
        }
        return persistentBoundMaterial();
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

    /** 持久化绑定材质（shelf.itemStack），用于渲染价格按钮。 */
    private Material persistentBoundMaterial() {
        ItemStack is = shelf.itemStack();
        return (is != null && !is.getType().isAir()) ? is.getType() : null;
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
     * 价格按钮：绑定后图标为绑定物品 Material + 附魔光效，未绑定为 GOLD_INGOT。
     */
    private static ItemStack createPriceButton(Material boundMaterial, double price, Messages messages) {
        boolean bound = boundMaterial != null && !boundMaterial.isAir();
        ItemStack item = new ItemStack(bound ? boundMaterial : Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get("gui-shelf-set-price"));
            meta.lore(List.of(
                    messages.get("gui-shelf-current-price", "price", String.format("%.2f", price)),
                    messages.get(bound ? "gui-shelf-bound" : "gui-shelf-unbound")));
            if (bound) {
                // 附魔光效：setEnchantmentGlintOverride 仅添加视觉光效，不附加伪附魔/无需隐藏 flag
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
}
