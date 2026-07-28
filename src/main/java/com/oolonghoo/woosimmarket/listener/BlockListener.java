package com.oolonghoo.woosimmarket.listener;

import com.oolonghoo.woosimmarket.WooSimMarket;
import com.oolonghoo.woosimmarket.config.Messages;
import com.oolonghoo.woosimmarket.gui.ShelfGui;
import com.oolonghoo.woosimmarket.gui.ShopGui;
import com.oolonghoo.woosimmarket.hook.CraftEngineHook;
import com.oolonghoo.woosimmarket.model.Shop;
import com.oolonghoo.woosimmarket.model.Shelf;
import com.oolonghoo.woosimmarket.shop.ShopManager;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 方块事件监听器。
 *
 * <p>处理收银机/货架的放置（创建商店/绑定货架）、破坏（清除记录）、右键交互（打开 GUI）。
 * 放置校验失败时取消事件（阻止放置）；破坏允许但清除关联记录。</p>
 *
 * <p>所有事件在主线程执行，无需额外同步。</p>
 *
 * @author oolongho
 */
public class BlockListener implements Listener {

    private final WooSimMarket plugin;
    private final ShopManager shopManager;
    private final CraftEngineHook craftEngine;
    private final Messages messages;

    public BlockListener(WooSimMarket plugin, ShopManager shopManager,
                         CraftEngineHook craftEngine, Messages messages) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.craftEngine = craftEngine;
        this.messages = messages;
    }

    /**
     * 放置方块：识别收银机 → 创建商店；识别货架 → 绑定最近商店。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!craftEngine.isReady()) {
            return;
        }

        if (craftEngine.isCashRegister(block)) {
            handleCashRegisterPlace(event, block);
        } else if (craftEngine.isShelf(block)) {
            handleShelfPlace(event, block);
        }
    }

    /**
     * 破坏方块：清除商店/货架记录。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!craftEngine.isReady()) {
            return;
        }

        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (craftEngine.isCashRegister(block)) {
            Shop shop = shopManager.getShopAt(world, x, y, z);
            if (shop != null) {
                shopManager.removeShop(shop.id());
                plugin.getLogger().info(() -> "商店 " + shop.id() + " 因方块破坏已移除");
            }
        } else if (craftEngine.isShelf(block)) {
            Shelf shelf = shopManager.getShelfAt(world, x, y, z);
            if (shelf != null) {
                shopManager.removeShelf(shelf.id());
            }
        }
    }

    /**
     * 右键交互：货架 → 打开 ShelfGui（仅所有者）；收银机 → 打开 ShopGui（仅所有者）。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !craftEngine.isReady()) {
            return;
        }

        // 玩家潜行时跳过 GUI（允许方块原生交互）
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;
        }

        if (craftEngine.isShelf(block)) {
            event.setCancelled(true);
            handleShelfInteract(player, block);
        } else if (craftEngine.isCashRegister(block)) {
            event.setCancelled(true);
            handleCashRegisterInteract(player, block);
        }
    }

    // ===== 内部处理 =====

    private void handleCashRegisterPlace(BlockPlaceEvent event, Block block) {
        Player player = event.getPlayer();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // 上限校验
        int limit = plugin.getConfigLoader().getShopLimit();
        if (shopManager.countShopsByOwner(player.getUniqueId()) >= limit) {
            event.setCancelled(true);
            messages.send(player, "shop-limit-reached");
            return;
        }

        // 距离校验
        double minDistance = plugin.getConfigLoader().getShopMinDistance();
        if (shopManager.isShopNear(world, x, y, z, minDistance)) {
            event.setCancelled(true);
            messages.send(player, "shop-too-close");
            return;
        }

        // 获取朝向
        String facing = getFacing(block);

        // 创建商店
        Shop shop = shopManager.createShop(player.getUniqueId(), world, x, y, z, facing);
        if (shop == null) {
            event.setCancelled(true);
            plugin.getLogger().severe(() -> "创建商店落库失败：玩家=" + player.getName() + " 位置=" + world + "," + x + "," + y + "," + z);
            return;
        }
        messages.send(player, "shop-created");
    }

    private void handleShelfPlace(BlockPlaceEvent event, Block block) {
        Player player = event.getPlayer();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // 查找半径内的最近商店
        double radius = plugin.getConfigLoader().getShopBindRadius();
        Shop nearestShop = shopManager.findNearestShop(world, x, y, z, radius);
        if (nearestShop == null) {
            event.setCancelled(true);
            messages.send(player, "shelf-too-far");
            return;
        }

        String facing = getFacing(block);
        Shelf shelf = shopManager.bindShelf(nearestShop.id(), world, x, y, z, facing);
        if (shelf == null) {
            event.setCancelled(true);
            plugin.getLogger().severe(() -> "绑定货架落库失败：玩家=" + player.getName());
            return;
        }
        messages.send(player, "shelf-bound");
    }

    private void handleShelfInteract(Player player, Block block) {
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        Shelf shelf = shopManager.getShelfAt(world, x, y, z);
        if (shelf == null) {
            return;
        }

        // 仅所有者可打开 GUI
        Shop shop = shopManager.getShop(shelf.shopId());
        if (shop == null || !shop.ownerUuid().equals(player.getUniqueId())) {
            return;
        }

        // 定价态中不打开 GUI
        if (plugin.getPricingManager().isPricing(player)) {
            return;
        }

        new ShelfGui(shelf, messages).open(player);
    }

    private void handleCashRegisterInteract(Player player, Block block) {
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        Shop shop = shopManager.getShopAt(world, x, y, z);
        if (shop == null) {
            return;
        }

        // 仅所有者可打开 GUI
        if (!shop.ownerUuid().equals(player.getUniqueId())) {
            return;
        }

        new ShopGui(shop, plugin.getEconomyManager(), messages).open(player);
    }

    /**
     * 获取方块朝向。非 Directional 方块默认 NORTH。
     */
    private static String getFacing(Block block) {
        if (block.getBlockData() instanceof Directional directional) {
            return directional.getFacing().name();
        }
        return "NORTH";
    }
}
