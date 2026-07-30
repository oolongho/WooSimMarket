package com.oolongho.woosimmarket.npc;

import com.oolongho.woosimmarket.config.ConfigLoader;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPC 随机装备生成器。
 *
 * <p>从 {@link ConfigLoader} 读取装备池配置，为 NPC 生成随机装备（胸甲/护腿/靴子/主手装饰物）。
 * 皮革材质装备应用随机染色。头盔始终为空（保留头部皮肤显示）。</p>
 *
 * <p>装备在 NPC 生成时一次性创建，存入 {@link SimNpc.Equipment}，由
 * {@link NpcPacketSender} 在 spawn 时通过 {@code ClientboundSetEquipmentPacket} 发送。
 * 无运行时 tick 开销。</p>
 *
 * @author oolongho
 */
public class EquipmentProvider {

    private final ConfigLoader configLoader;

    public EquipmentProvider(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /**
     * 生成一套随机装备。
     *
     * <p>从配置池随机抽取 4 部位装备，皮革装备应用随机染色。
     * equipment 禁用或池为空时对应部位返回 null（不穿戴）。</p>
     *
     * @return 装备对象（可能为全空 {@link SimNpc.Equipment#EMPTY}）
     */
    public SimNpc.Equipment random() {
        if (!configLoader.isNpcEquipmentEnabled()) {
            return SimNpc.Equipment.EMPTY;
        }
        ItemStack chestplate = pick(configLoader.getEquipmentChestplate());
        ItemStack leggings = pick(configLoader.getEquipmentLeggings());
        ItemStack boots = pick(configLoader.getEquipmentBoots());
        ItemStack mainHand = pick(configLoader.getEquipmentMainHand());
        return new SimNpc.Equipment(chestplate, leggings, boots, mainHand);
    }

    /**
     * 从装备池随机抽取一个物品。
     *
     * <p>AIR 或无效 Material 返回 null（不穿戴）。皮革材质装备应用随机染色。</p>
     *
     * @param pool Material 名列表
     * @return 物品；空池/AIR/无效返回 null
     */
    private ItemStack pick(List<String> pool) {
        if (pool.isEmpty()) {
            return null;
        }
        String name = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Material material = Material.matchMaterial(name);
        if (material == null || material == Material.AIR) {
            return null;
        }
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta leatherMeta) {
            List<Color> colors = configLoader.getLeatherColors();
            if (!colors.isEmpty()) {
                leatherMeta.setColor(colors.get(ThreadLocalRandom.current().nextInt(colors.size())));
                item.setItemMeta(leatherMeta);
            }
        }
        return item;
    }
}
