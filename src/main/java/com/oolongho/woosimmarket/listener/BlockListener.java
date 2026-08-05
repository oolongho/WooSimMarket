package com.oolongho.woosimmarket.listener;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.gui.ShelfGui;
import com.oolongho.woosimmarket.gui.ShopGui;
import com.oolongho.woosimmarket.hook.CraftEngineHook;
import com.oolongho.woosimmarket.model.Shop;
import com.oolongho.woosimmarket.model.Shelf;
import com.oolongho.woosimmarket.shop.ShopManager;
import com.oolongho.woosimmarket.util.SchedulerUtil;
import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import com.oolongho.woosimmarket.visualize.ShopDisplayManager;
import com.oolongho.woosimmarket.visualize.ShopRangeVisualizer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 方块事件监听器。
 *
 * <p>处理收银机/货架的放置（创建商店/绑定货架）、破坏（清除记录）、右键交互（打开 GUI）。
 * 放置校验失败时取消事件（阻止放置）；破坏允许但清除关联记录。
 * 爆炸/火焰保护：从爆炸方块列表移除收银机/货架、取消火焰燃烧，避免库存/余额丢失。</p>
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
    private final ShelfDisplayManager shelfDisplayManager;
    private final ShopDisplayManager shopDisplayManager;
    private final ShopRangeVisualizer shopRangeVisualizer;

    public BlockListener(WooSimMarket plugin, ShopManager shopManager,
                         CraftEngineHook craftEngine, Messages messages,
                         ShelfDisplayManager shelfDisplayManager,
                         ShopDisplayManager shopDisplayManager,
                         ShopRangeVisualizer shopRangeVisualizer) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.craftEngine = craftEngine;
        this.messages = messages;
        this.shelfDisplayManager = shelfDisplayManager;
        this.shopDisplayManager = shopDisplayManager;
        this.shopRangeVisualizer = shopRangeVisualizer;
    }

    /**
     * 放置方块：识别收银机 → 创建商店；识别货架 → 绑定最近商店。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();

        if (craftEngine.isCashRegister(block)) {
            handleCashRegisterPlace(event, block);
        } else if (craftEngine.isShelf(block)) {
            handleShelfPlace(event, block);
        }
    }

    /**
     * 破坏方块：清除商店/货架记录。
     *
     * <p>收银机仅店长可破坏，破坏时自动提现余额；货架破坏前掉落全部库存，避免物品静默丢失。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (craftEngine.isCashRegister(block)) {
            Shop shop = shopManager.getShopAt(world, x, y, z);
            if (shop != null) {
                // 仅店长可破坏收银机
                Player player = event.getPlayer();
                if (!player.getUniqueId().equals(shop.ownerUuid())) {
                    event.setCancelled(true);
                    messages.send(player, "shop-not-owner");
                    return;
                }

                // 自动提现：将商店余额全额转入店长 Vault 账户
                // 失败时取消破坏并提示，避免余额随商店删除而丢失
                if (shop.balance() > 0) {
                    double amount = shop.balance();
                    if (!plugin.getEconomyManager().deposit(player, amount)) {
                        event.setCancelled(true);
                        messages.send(player, "withdraw-failed");
                        return;
                    }
                    messages.send(player, "shop-auto-withdraw", "amount",
                            plugin.getEconomyManager().format(amount));
                }

                // 必须在 removeShop 之前移除展示：removeShop 会清空关联货架索引，
                // 之后再调用 removeShelvesByShop 将查不到货架
                shopDisplayManager.removeDisplayByShop(shop.id());
                shelfDisplayManager.removeShelvesByShop(shop.id());
                shopManager.removeShop(shop.id());
                plugin.getLogger().info(() -> "商店 " + shop.id() + " 因方块破坏已移除");
            }
        } else if (craftEngine.isShelf(block)) {
            Shelf shelf = shopManager.getShelfAt(world, x, y, z);
            if (shelf != null) {
                // 破坏前掉落货架内全部物品（含绑定模板），避免物品静默丢失
                if (shelf.stock() > 0 && shelf.itemStack() != null) {
                    ItemStack template = shelf.itemStack().clone();
                    int totalStock = shelf.stock();
                    int maxStack = template.getMaxStackSize();
                    Location dropLoc = block.getLocation();
                    World dropWorld = block.getWorld();
                    // Folia 上 dropItemNaturally 需在掉落位置所属区域线程执行
                    // 玩家在区域边界破坏方块时，block 可能在另一区域
                    SchedulerUtil.runTaskAt(dropLoc, () -> {
                        int remaining = totalStock;
                        while (remaining > 0) {
                            int amt = Math.min(remaining, maxStack);
                            ItemStack drop = template.clone();
                            drop.setAmount(amt);
                            dropWorld.dropItemNaturally(dropLoc, drop);
                            remaining -= amt;
                        }
                    });
                }
                shopManager.removeShelf(shelf.id());
                shelfDisplayManager.removeDisplay(shelf.id());
            }
        }
    }

    /**
     * 实体爆炸保护：从爆炸方块列表移除收银机/货架，防止物品/余额丢失。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedBlock);
    }

    /**
     * 方块爆炸保护（床/重生锚等）：从爆炸方块列表移除收银机/货架。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedBlock);
    }

    /**
     * 火焰燃烧保护：取消收银机/货架的燃烧，防止数据丢失。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (isProtectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 判断方块是否为受保护的收银机/货架。 */
    private boolean isProtectedBlock(Block block) {
        return craftEngine.isCashRegister(block) || craftEngine.isShelf(block);
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
        if (block == null) {
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
        int limit = plugin.getConfigLoader().getShopMaxShopsPerPlayer();
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

        // 创建商店（默认店名取 ownerName + " 的小店"，玩家可在商店面板点击改名）
        String defaultName = messages.getRaw("shop-default-name").replace("{owner}", player.getName());
        Shop shop = shopManager.createShop(player.getUniqueId(), world, x, y, z, facing, defaultName);
        if (shop == null) {
            event.setCancelled(true);
            plugin.getLogger().severe(() -> "创建商店落库失败：玩家=" + player.getName() + " 位置=" + world + "," + x + "," + y + "," + z);
            return;
        }
        messages.send(player, "shop-created");
        shopDisplayManager.spawnDisplay(shop);
        shopRangeVisualizer.showRange(player, shop);
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

        // 改名态中不打开 GUI
        if (plugin.getShopNamingManager().isNaming(player)) {
            return;
        }

        new ShopGui(shop, plugin.getEconomyManager(), messages, shopManager).open(player);
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
