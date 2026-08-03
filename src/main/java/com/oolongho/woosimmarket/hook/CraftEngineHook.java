package com.oolongho.woosimmarket.hook;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.registry.Holder;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * CraftEngine 方块钩子
 *
 * <p>负责检测 CraftEngine 是否在线，并提供对收银台/货架方块的识别能力。
 * 方块 ID 由 {@link ConfigLoader} 配置驱动，支持两种格式：
 * <ul>
 *   <li>含 {@code :} → CraftEngine 自定义方块（namespace:path），需 CraftEngine 在线</li>
 *   <li>不含 {@code :} → 原版 {@link Material} 名，无需 CraftEngine</li>
 * </ul>
 * 配置解析失败时兜底为原版 {@link Material#EMERALD_BLOCK}（收银台）/
 * {@link Material#CHISELED_BOOKSHELF}（货架），确保插件基本可用。</p>
 *
 * @author oolongho
 */
public class CraftEngineHook {

    private static final String CE_PLUGIN_NAME = "CraftEngine";
    /** 收银台兜底方块（配置无效时）。 */
    private static final Material FALLBACK_CASH_REGISTER = Material.EMERALD_BLOCK;
    /** 货架兜底方块（配置无效时）。 */
    private static final Material FALLBACK_SHELF = Material.CHISELED_BOOKSHELF;

    private final WooSimMarket plugin;
    /** 收银台方块标识：CraftEngine Key 或 null（原版 Material 模式）。 */
    private final Key cashRegisterKey;
    /** 货架方块标识：CraftEngine Key 或 null（原版 Material 模式）。 */
    private final Key shelfKey;
    /** 收银台原版 Material（CraftEngine 模式下为 null）。 */
    private final Material cashRegisterMaterial;
    /** 货架原版 Material（CraftEngine 模式下为 null）。 */
    private final Material shelfMaterial;
    private volatile boolean ready = false;

    /**
     * 构造钩子，从配置解析收银台/货架方块标识。
     *
     * @param plugin       插件实例
     * @param configLoader 配置加载器
     */
    public CraftEngineHook(WooSimMarket plugin, ConfigLoader configLoader) {
        this.plugin = plugin;
        String cashRegisterId = configLoader.getCashRegisterBlock();
        String shelfId = configLoader.getShelfBlock();

        Key parsedCashKey = null;
        Material parsedCashMaterial = null;
        Key parsedShelfKey = null;
        Material parsedShelfMaterial = null;

        try {
            if (cashRegisterId.contains(":")) {
                parsedCashKey = Key.of(cashRegisterId);
            } else {
                parsedCashMaterial = Material.valueOf(cashRegisterId.toUpperCase());
            }
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "收银台方块配置无效：" + cashRegisterId
                    + "，兜底为 " + FALLBACK_CASH_REGISTER.name());
            parsedCashMaterial = FALLBACK_CASH_REGISTER;
        }

        try {
            if (shelfId.contains(":")) {
                parsedShelfKey = Key.of(shelfId);
            } else {
                parsedShelfMaterial = Material.valueOf(shelfId.toUpperCase());
            }
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "货架方块配置无效：" + shelfId
                    + "，兜底为 " + FALLBACK_SHELF.name());
            parsedShelfMaterial = FALLBACK_SHELF;
        }

        this.cashRegisterKey = parsedCashKey;
        this.cashRegisterMaterial = parsedCashMaterial;
        this.shelfKey = parsedShelfKey;
        this.shelfMaterial = parsedShelfMaterial;
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
        if (cashRegisterKey != null || shelfKey != null) {
            plugin.getLogger().warning(() -> "未找到 CraftEngine！配置的 CraftEngine 自定义方块将不可用，"
                    + "原版方块配置仍可工作");
        } else {
            plugin.getLogger().info(() -> "未找到 CraftEngine，使用原版方块模式");
        }
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
     * 判断方块是否为收银台。
     *
     * <p>CraftEngine Key 模式需 CraftEngine ready；原版 Material 模式不需。</p>
     *
     * @param block 方块
     * @return 是收银台返回 true
     */
    public boolean isCashRegister(Block block) {
        if (cashRegisterKey != null) {
            return isBlockOfType(block, cashRegisterKey);
        }
        return block != null && block.getType() == cashRegisterMaterial;
    }

    /**
     * 判断方块是否为货架。
     *
     * <p>CraftEngine Key 模式需 CraftEngine ready；原版 Material 模式不需。</p>
     *
     * @param block 方块
     * @return 是货架返回 true
     */
    public boolean isShelf(Block block) {
        if (shelfKey != null) {
            return isBlockOfType(block, shelfKey);
        }
        return block != null && block.getType() == shelfMaterial;
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
     * 创建收银台或货架方块的物品形式（用于 /wsm give 命令）。
     *
     * <p>CraftEngine Key 模式：通过方块 Key 获取 {@link BlockDefinition} → 关联物品 Key →
     * {@link BukkitItemDefinition} 构建 {@link ItemStack}。
     * 原版 Material 模式：直接 {@code new ItemStack(material)}。</p>
     *
     * @param blockType 方块类型名（cash_register 或 shelf）
     * @return 物品；不可用或获取失败返回 null
     */
    @Nullable
    public ItemStack createBlockItemStack(String blockType) {
        if (blockType == null) {
            return null;
        }
        boolean isCashRegister = "cash_register".equalsIgnoreCase(blockType);
        boolean isShelf = "shelf".equalsIgnoreCase(blockType);
        if (!isCashRegister && !isShelf) {
            return null;
        }

        Key blockKey = isCashRegister ? cashRegisterKey : shelfKey;
        Material material = isCashRegister ? cashRegisterMaterial : shelfMaterial;

        // 原版 Material 模式
        if (blockKey == null) {
            return material == null ? null : new ItemStack(material);
        }

        // CraftEngine Key 模式
        if (!ready) {
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

    /**
     * 获取物品的 itemId（原版 = {@link Material#name()}，CE 物品 = {@code namespace:path}）。
     *
     * <p>CE 未就绪或非 CE 物品返回 {@link Material#name()}。</p>
     *
     * @param itemStack 物品
     * @return itemId；itemStack 为 null 返回 null
     */
    @Nullable
    public String getItemId(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        if (ready && CraftEngineItems.isCustomItem(itemStack)) {
            try {
                Key customId = CraftEngineItems.getCustomItemId(itemStack);
                if (customId != null) {
                    return customId.asString();
                }
            } catch (Exception ignored) {
            }
        }
        return itemStack.getType().name();
    }

    /**
     * 根据 itemId 构造 {@link ItemStack}。
     *
     * <p>CE 物品（含 {@code :}）走 {@link CraftEngineItems#byId(Key)} →
     * {@link BukkitItemDefinition#buildBukkitItem()}；原版走 {@link Material#matchMaterial(String)}；
     * 失败回退 {@link Material#PAPER}。</p>
     *
     * @param itemId 物品 ID
     * @return 物品；itemId 为 null 返回 PAPER
     */
    public ItemStack createItemStack(String itemId) {
        if (itemId == null) {
            return new ItemStack(Material.PAPER);
        }
        if (ready && itemId.contains(":")) {
            try {
                BukkitItemDefinition def = CraftEngineItems.byId(Key.of(itemId));
                if (def != null) {
                    return def.buildBukkitItem();
                }
            } catch (Exception ignored) {
            }
        }
        Material material = Material.matchMaterial(itemId);
        return material == null ? new ItemStack(Material.PAPER) : new ItemStack(material);
    }

    /**
     * 构造物品展示名的 translatable 组件。
     *
     * <p>原版物品用 {@link Material#translationKey()} 让客户端翻译；
     * CE 物品尝试取 {@link BukkitItemDefinition#translationKey()}，无则回退 {@code text(itemId)}。
     * CE 未就绪时，CE 物品（含 {@code :}）回退 {@code text(itemId)}，原版物品仍走 translationKey。</p>
     *
     * @param itemId 物品 ID
     * @return 展示名组件；itemId 为 null 返回 {@code text("?")}
     */
    public Component displayName(String itemId) {
        if (itemId == null) {
            return Component.text("?");
        }
        if (ready && itemId.contains(":")) {
            try {
                BukkitItemDefinition def = CraftEngineItems.byId(Key.of(itemId));
                if (def != null) {
                    String translationKey = def.translationKey();
                    if (translationKey != null) {
                        return Component.translatable(translationKey);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Material material = Material.matchMaterial(itemId);
        if (material != null) {
            return Component.translatable(material.translationKey());
        }
        return Component.text(itemId);
    }
}
