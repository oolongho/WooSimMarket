package com.oolongho.woosimmarket.hook;

import com.oolongho.woosimmarket.WooSimMarket;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.registry.Holder;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * CraftEngine 方块钩子
 *
 * <p>负责检测 CraftEngine 是否在线，并提供对自定义方块的识别能力。
 * 收银机方块 Key 为 {@code simmarket:cash_register}，货架方块 Key 为 {@code simmarket:shelf}。
 * 关键 Key 对象在构造时缓存，避免每次比对重复创建。</p>
 *
 * @author oolongho
 */
public class CraftEngineHook {

    private static final String CE_PLUGIN_NAME = "CraftEngine";
    private static final String NAMESPACE = "simmarket";
    private static final String CASH_REGISTER_PATH = "cash_register";
    private static final String SHELF_PATH = "shelf";

    private final WooSimMarket plugin;
    private final Key cashRegisterKey;
    private final Key shelfKey;
    private volatile boolean ready = false;

    /**
     * 构造钩子，预构造关键 Key 用于后续比对。
     *
     * @param plugin 插件实例
     */
    public CraftEngineHook(WooSimMarket plugin) {
        this.plugin = plugin;
        this.cashRegisterKey = Key.of(NAMESPACE, CASH_REGISTER_PATH);
        this.shelfKey = Key.of(NAMESPACE, SHELF_PATH);
    }

    /**
     * 检测 CraftEngine 是否在线并记录日志。
     *
     * @return 在线返回 true，离线返回 false
     */
    public boolean init() {
        if (Bukkit.getPluginManager().getPlugin(CE_PLUGIN_NAME) != null) {
            ready = true;
            plugin.getLogger().info(() -> "已挂钩 CraftEngine：自定义方块识别就绪");
            return true;
        }
        ready = false;
        plugin.getLogger().severe(() -> "未找到 CraftEngine！WooSimMarket 自定义方块识别将不可用（收银机/货架无法工作）");
        return false;
    }

    /**
     * CraftEngine 是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 判断方块是否为收银机（{@code simmarket:cash_register}）。
     *
     * @param block 方块
     * @return 是收银机返回 true
     */
    public boolean isCashRegister(Block block) {
        return isBlockOfType(block, cashRegisterKey);
    }

    /**
     * 判断方块是否为货架（{@code simmarket:shelf}）。
     *
     * @param block 方块
     * @return 是货架返回 true
     */
    public boolean isShelf(Block block) {
        return isBlockOfType(block, shelfKey);
    }

    /**
     * 通用自定义方块判断，转发至 {@link CraftEngineBlocks#isCustomBlock(Block)}。
     *
     * @param block 方块
     * @return 是自定义方块返回 true
     */
    public boolean isCustomBlock(Block block) {
        if (!ready || block == null) {
            return false;
        }
        return CraftEngineBlocks.isCustomBlock(block);
    }

    /**
     * 获取方块的自定义 Key 字符串（如 {@code simmarket:cash_register}）。
     *
     * @param block 方块
     * @return Key 字符串；非自定义方块或不可用返回 null
     */
    @Nullable
    public String getCustomBlockId(Block block) {
        Key key = getBlockKey(block);
        return key == null ? null : key.asString();
    }

    /**
     * 判断方块是否为指定 Key 的自定义方块。
     *
     * @param block    方块
     * @param expected 期望的 Key
     * @return 匹配返回 true
     */
    private boolean isBlockOfType(Block block, Key expected) {
        Key key = getBlockKey(block);
        return key != null && key.equals(expected);
    }

    /**
     * 取方块对应的自定义 Key。
     *
     * <p>路径：{@link CraftEngineBlocks#getCustomBlockState(Block)} → {@link ImmutableBlockState#owner()}
     * → {@link Holder#value()} → {@link BlockDefinition#id()}。Holder 未绑定时返回 null（防御）。</p>
     *
     * @param block 方块
     * @return Key；非自定义方块或不可用或未绑定返回 null
     */
    @Nullable
    private Key getBlockKey(Block block) {
        if (!ready || block == null) {
            return null;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null) {
            return null;
        }
        Holder<BlockDefinition> holder = state.owner();
        if (holder == null || !holder.isBound()) {
            return null;
        }
        return holder.value().id();
    }

    /**
     * 创建指定方块类型的物品形式（用于 /wsm give 命令）。
     *
     * <p>路径：通过方块 Key 获取 {@link BlockDefinition} →
     * {@code defaultState().settings().itemId()} 获取关联物品 Key →
     * {@link CraftEngineItems#byId(Key)} 获取 {@link BukkitItemDefinition} →
     * {@code buildBukkitItem()} 构建 {@link ItemStack}。</p>
     *
     * @param blockType 方块类型名（cash_register 或 shelf）
     * @return 物品；不可用或获取失败返回 null
     */
    @Nullable
    public ItemStack createBlockItemStack(String blockType) {
        if (!ready || blockType == null) {
            return null;
        }
        Key blockKey = switch (blockType.toLowerCase()) {
            case CASH_REGISTER_PATH -> cashRegisterKey;
            case SHELF_PATH -> shelfKey;
            default -> null;
        };
        if (blockKey == null) {
            return null;
        }
        BlockDefinition definition = CraftEngineBlocks.byId(blockKey);
        if (definition == null) {
            return null;
        }
        ImmutableBlockState defaultState = definition.defaultState();
        if (defaultState == null) {
            return null;
        }
        Key itemKey = defaultState.settings().itemId();
        if (itemKey == null) {
            return null;
        }
        BukkitItemDefinition itemDefinition = CraftEngineItems.byId(itemKey);
        if (itemDefinition == null) {
            return null;
        }
        return itemDefinition.buildBukkitItem();
    }
}
