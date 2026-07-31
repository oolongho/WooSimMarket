package com.oolongho.woosimmarket.npc;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯发包 NPC 数据包发送器。
 *
 * <p>负责向客户端发送 NPC 的生成、移动、移除数据包。不创建任何服务端实体，
 * 所有 NPC 仅存在于客户端渲染层面。</p>
 *
 * <p>包序列（spawn）：
 * <ol>
 *   <li>{@link ClientboundPlayerInfoUpdatePacket}（ADD_PLAYER）— 添加到 TAB 列表</li>
 *   <li>{@link ClientboundAddEntityPacket} — 生成玩家实体（26.1+ 替代已移除的 ClientboundAddPlayerPacket）</li>
 *   <li>{@link ClientboundSetEntityDataPacket} — 设置 skin 外层 metadata（DATA_PLAYER_MODE_CUSTOMISATION）</li>
 *   <li>{@link ClientboundPlayerInfoUpdatePacket#updateListed} — 从 TAB 列表隐藏</li>
 *   <li>{@link ClientboundRotateHeadPacket} — 头部朝向与 body yaw 一致</li>
 * </ol></p>
 *
 * <p>包序列（remove）：
 * <ol>
 *   <li>{@link ClientboundRemoveEntitiesPacket} — 移除实体</li>
 *   <li>{@link ClientboundPlayerInfoRemovePacket} — 从 TAB 列表移除</li>
 * </ol></p>
 *
 * <p>移动使用 {@link ClientboundMoveEntityPacket.PosRot}（相对位移），位移过大时
 * 降级为 {@link ClientboundTeleportEntityPacket}（绝对位置，基于 {@link PositionMoveRotation}）。</p>
 *
 * @author oolongho
 */
public class NpcPacketSender {

    /** 移动包最大相对位移（方块），超过则降级为 Teleport。 */
    private static final double MAX_MOVE_DELTA = 7.0;

    /** Minecraft 玩家名上限（GameProfile.name 长度限制）。 */
    private static final int MC_NAME_LIMIT = 16;

    /** 每个 NPC 上次发送给各客户端的位置（entityId → playerUuid → location），用于计算相对位移。 */
    private final Map<Integer, Map<UUID, Location>> lastSentByNpc = new ConcurrentHashMap<>();

    /** NPC entityId → 已收到 spawn 包的玩家 UUID 集合（用于 auto-spawn 与精确移除）。 */
    private final Map<Integer, Set<UUID>> playersByNpc = new ConcurrentHashMap<>();

    /** NPC entityId → 上次发送的 head yaw（用于判断是否需要更新头部朝向）。 */
    private final Map<Integer, Float> lastHeadYaws = new ConcurrentHashMap<>();

    /** 配置加载器，用于读取 skin-parts 位掩码。 */
    private final ConfigLoader configLoader;

    /** 消息管理器，用于查询性格前缀（MiniMessage 字符串）。 */
    private final Messages messages;

    /** MiniMessage 解析器（前缀 → Component）。 */
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** 纯文本序列化器（剥离 MiniMessage 标签后取可见字符）。 */
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    /** DATA_PLAYER_MODE_CUSTOMISATION 的 entity data id（构造时反射读取，失败为 -1 降级）。 */
    private final int playerModeCustomisationId;

    /** DATA_CUSTOM_NAME 的 entity data id（构造时反射读取，失败为 -1 降级：不发自定义名标签）。 */
    private final int customNameId;

    /** DATA_CUSTOM_NAME_VISIBLE 的 entity data id（构造时反射读取，失败为 -1 降级）。 */
    private final int customNameVisibleId;

    public NpcPacketSender(ConfigLoader configLoader, Messages messages) {
        this.configLoader = configLoader;
        this.messages = messages;
        this.playerModeCustomisationId = resolveEntityDataId(Avatar.DATA_PLAYER_MODE_CUSTOMISATION);
        this.customNameId = resolveEntityDataIdByName(net.minecraft.world.entity.Entity.class, "DATA_CUSTOM_NAME");
        this.customNameVisibleId = resolveEntityDataIdByName(net.minecraft.world.entity.Entity.class, "DATA_CUSTOM_NAME_VISIBLE");
    }

    /**
     * 向单个玩家发送 NPC 生成包，并记录追踪关系。
     *
     * @param player 目标玩家
     * @param npc    NPC
     */
    public void spawn(Player player, SimNpc npc) {
        GameProfile profile = createProfile(npc);
        Component displayName = buildDisplayName(npc);
        Location loc = npc.location();

        // 1. 添加到 TAB 列表（携带性格前缀显示名，TAB 列表隐藏不影响显示名透传）
        sendPacket(player, createInfoAddPacket(profile, displayName));

        // 2. 生成玩家实体（26.1+ 使用 ClientboundAddEntityPacket）
        sendPacket(player, new ClientboundAddEntityPacket(
                npc.entityId(), npc.uuid(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw(),
                EntityType.PLAYER, 0,
                Vec3.ZERO, 0.0));

        // 3. 设置 skin 外层 metadata（反射失败时跳过，降级为无外层，NPC 仍可正常工作）
        if (playerModeCustomisationId >= 0) {
            sendPacket(player, createSkinPartsPacket(npc.entityId(), configLoader.getNpcSkinParts()));
        }

        // 3.5 设置自定义名标签（性格前缀 + baseName，含 MiniMessage 颜色）
        //    反射失败或前缀缺失时跳过；GameProfile.name 已含纯文本前缀作为降级显示
        if (customNameId >= 0 && customNameVisibleId >= 0 && !npc.personality().name().equals("normal")) {
            sendPacket(player, createCustomNamePacket(npc.entityId(), displayName));
        }

        // 3.6 发送随机装备（4 部位，头盔始终为空保留头部皮肤；全空时跳过）
        if (!npc.equipment().isEmpty()) {
            sendPacket(player, createEquipmentPacket(npc.entityId(), npc.equipment()));
        }

        // 4. 从 TAB 列表隐藏
        sendPacket(player, ClientboundPlayerInfoUpdatePacket.updateListed(npc.uuid(), false));

        // 5. 设置头部朝向与身体一致（spawn 时强制发送一次，避免客户端头部朝默认方向）
        //    注意：不更新 lastHeadYaws —— 该共享状态由 moveToNearby 的广播统一管理，
        //    此处更新会抑制同 tick 内其他已追踪玩家的头部旋转广播
        sendPacket(player, createHeadRotatePacket(npc.entityId(), loc.getYaw()));

        // 记录上次发送位置 + 追踪关系
        lastSentByNpc.computeIfAbsent(npc.entityId(), k -> new ConcurrentHashMap<>())
                .put(player.getUniqueId(), loc.clone());
        playersByNpc.computeIfAbsent(npc.entityId(), k -> ConcurrentHashMap.newKeySet())
                .add(player.getUniqueId());
    }

    /**
     * 向单个玩家发送 NPC 移动包。若玩家尚未收到 spawn 包，自动先发送 spawn。
     *
     * <p>这解决了玩家加入服务器、传送或走回 NPC 视距内时看不到已有 NPC 的问题 ——
     * 无需 PlayerJoinEvent/PlayerTeleportEvent 显式处理，下一 tick 的
     * {@link #moveToNearby} 自然触发 auto-spawn。</p>
     *
     * @param player 目标玩家
     * @param npc    NPC
     */
    public void move(Player player, SimNpc npc) {
        // Auto-spawn：玩家尚未收到 spawn 包时，先 spawn 再 return（spawn 已设置位置）
        Set<UUID> tracked = playersByNpc.get(npc.entityId());
        if (tracked == null || !tracked.contains(player.getUniqueId())) {
            spawn(player, npc);
            return;
        }

        Location current = npc.location();
        Map<UUID, Location> sentMap = lastSentByNpc.get(npc.entityId());
        Location last = (sentMap != null) ? sentMap.get(player.getUniqueId()) : null;

        if (last == null) {
            // 无上次位置记录，发送 Teleport
            sendPacket(player, createTeleportPacket(npc));
        } else {
            double dx = current.getX() - last.getX();
            double dy = current.getY() - last.getY();
            double dz = current.getZ() - last.getZ();

            if (Math.abs(dx) > MAX_MOVE_DELTA || Math.abs(dy) > MAX_MOVE_DELTA || Math.abs(dz) > MAX_MOVE_DELTA) {
                // 位移过大，降级为 Teleport
                sendPacket(player, createTeleportPacket(npc));
            } else {
                sendPacket(player, new ClientboundMoveEntityPacket.PosRot(
                        npc.entityId(),
                        (short) (dx * 4096),
                        (short) (dy * 4096),
                        (short) (dz * 4096),
                        toAngle(current.getYaw()),
                        toAngle(current.getPitch()),
                        true));
            }
        }
        // 更新该玩家的上次发送位置
        lastSentByNpc.computeIfAbsent(npc.entityId(), k -> new ConcurrentHashMap<>())
                .put(player.getUniqueId(), current.clone());
    }

    /**
     * 向 NPC 视距内所有玩家广播生成包。
     *
     * @param npc    NPC
     * @param radius 广播半径（方块）
     */
    public void spawnToNearby(SimNpc npc, double radius) {
        for (Player p : getNearbyPlayers(npc.location(), radius)) {
            spawn(p, npc);
        }
    }

    /**
     * 向 NPC 视距内所有玩家广播移动包。
     *
     * <p>头部旋转在此方法统一处理（而非在 {@link #move} 中），避免 per-NPC 共享的
     * lastHeadYaw 导致部分玩家漏收头部旋转包（仅循环中首个玩家收到，后续玩家
     * angleDiff=0 跳过）——此类漏收会随 NPC 转向不断累积，最终头部与身体方向背离。</p>
     *
     * @param npc    NPC
     * @param radius 广播半径（方块）
     */
    public void moveToNearby(SimNpc npc, double radius) {
        Collection<Player> players = getNearbyPlayers(npc.location(), radius);

        // 1. 逐玩家发送移动包（含 auto-spawn，spawn 内含头部旋转包）
        for (Player p : players) {
            move(p, npc);
        }

        // 2. 头部旋转广播：yaw 变化 > 15° 时，对所有已追踪玩家统一发送
        float currentYaw = npc.location().getYaw();
        Float lastHeadYaw = lastHeadYaws.get(npc.entityId());
        if (lastHeadYaw == null || angleDiff(currentYaw, lastHeadYaw) > 15.0f) {
            ClientboundRotateHeadPacket headPacket = createHeadRotatePacket(npc.entityId(), currentYaw);
            Set<UUID> tracked = playersByNpc.get(npc.entityId());
            if (tracked != null) {
                for (Player p : players) {
                    if (tracked.contains(p.getUniqueId())) {
                        sendPacket(p, headPacket);
                    }
                }
            }
            lastHeadYaws.put(npc.entityId(), currentYaw);
        }
    }

    /**
     * 向所有曾收到 spawn 包的玩家发送 NPC 移除包（用于 NPC 销毁）。
     *
     * <p>确保已离开广播半径但客户端仍持有实体的玩家也收到移除包，
     * 防止幽灵 NPC 残留。</p>
     *
     * @param npc NPC
     */
    public void removeFromAllTracked(SimNpc npc) {
        Set<UUID> players = playersByNpc.remove(npc.entityId());
        if (players != null) {
            ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(npc.entityId());
            ClientboundPlayerInfoRemovePacket infoPacket = new ClientboundPlayerInfoRemovePacket(List.of(npc.uuid()));
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    sendPacket(p, removePacket);
                    sendPacket(p, infoPacket);
                }
            }
        }
        lastSentByNpc.remove(npc.entityId());
        lastHeadYaws.remove(npc.entityId());
    }

    /**
     * 清理指定玩家的所有追踪记录（用于玩家退出/切换世界）。
     *
     * <p>不发送移除包 —— 客户端断开连接或切换世界时自动销毁所有实体。</p>
     *
     * @param playerUuid 玩家 UUID
     */
    public void removePlayer(UUID playerUuid) {
        List<Integer> toClean = new ArrayList<>();
        for (var entry : playersByNpc.entrySet()) {
            if (entry.getValue().remove(playerUuid) && entry.getValue().isEmpty()) {
                toClean.add(entry.getKey());
            }
        }
        for (int entityId : toClean) {
            playersByNpc.remove(entityId);
            lastSentByNpc.remove(entityId);
        }
        // 清理残留的 per-player 位置记录（NPC 仍有其他玩家追踪时内层 map 不为空）
        for (var sentMap : lastSentByNpc.values()) {
            sentMap.remove(playerUuid);
        }
    }

    /**
     * 清理所有位置缓存与追踪记录。
     */
    public void clearAllCache() {
        lastSentByNpc.clear();
        playersByNpc.clear();
        lastHeadYaws.clear();
    }

    // ===== 内部方法 =====

    private GameProfile createProfile(SimNpc npc) {
        // GameProfile.name 使用纯文本前缀+baseName（截断到 16 字符），作为 CustomName 反射失败时的降级显示
        String displayName = buildPlainDisplayName(npc);
        SimNpc.SkinData skin = npc.skin();
        if (skin != null && skin.value() != null && !skin.value().isEmpty()) {
            Multimap<String, Property> multimap = HashMultimap.create();
            multimap.put("textures", new Property("textures", skin.value(), skin.signature()));
            return new GameProfile(npc.uuid(), displayName, new PropertyMap(multimap));
        }
        return new GameProfile(npc.uuid(), displayName);
    }

    private ClientboundPlayerInfoUpdatePacket createInfoAddPacket(GameProfile profile, Component displayName) {
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.id(), profile, false, 0, GameType.SURVIVAL,
                PaperAdventure.asVanilla(displayName), false, 0, null);
        return new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(entry));
    }

    private ClientboundTeleportEntityPacket createTeleportPacket(SimNpc npc) {
        Location loc = npc.location();
        PositionMoveRotation pmr = new PositionMoveRotation(
                new Vec3(loc.getX(), loc.getY(), loc.getZ()),
                Vec3.ZERO,
                loc.getYaw(),
                loc.getPitch());
        return new ClientboundTeleportEntityPacket(npc.entityId(), pmr, Set.of(), false);
    }

    /**
     * 构造 skin 外层 metadata 包，设置 {@code DATA_PLAYER_MODE_CUSTOMISATION} 为指定位掩码。
     *
     * <p>位定义（与 Mojang 一致）：0x01 Cape | 0x02 Jacket | 0x04 LSleeve | 0x08 RSleeve |
     * 0x10 LPants | 0x20 RPants | 0x40 Hat；0xFF 全开。发包 NPC 无真实实体，直接构造
     * {@link SynchedEntityData.DataValue} 并装入 {@link ClientboundSetEntityDataPacket}。</p>
     *
     * @param entityId  NPC entityId
     * @param skinParts skin 外层位掩码
     * @return metadata 包
     */
    private ClientboundSetEntityDataPacket createSkinPartsPacket(int entityId, int skinParts) {
        SynchedEntityData.DataValue<?> dataValue = new SynchedEntityData.DataValue<>(
                playerModeCustomisationId, EntityDataSerializers.BYTE, (byte) skinParts);
        return new ClientboundSetEntityDataPacket(entityId, List.of(dataValue));
    }

    /**
     * 构造装备包，发送 NPC 的 4 部位装备（胸甲/护腿/靴子/主手）。
     *
     * <p>头盔始终不发送（保留头部皮肤显示）。null 部位不加入列表（客户端保持空）。
     * Bukkit ItemStack 通过 {@link CraftItemStack#asNMSCopy} 转为 NMS ItemStack。</p>
     *
     * @param entityId  NPC entityId
     * @param equipment 装备对象
     * @return 装备包
     */
    private ClientboundSetEquipmentPacket createEquipmentPacket(int entityId, SimNpc.Equipment equipment) {
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> list = new ArrayList<>();
        if (equipment.chestplate() != null) {
            list.add(Pair.of(EquipmentSlot.CHEST, CraftItemStack.asNMSCopy(equipment.chestplate())));
        }
        if (equipment.leggings() != null) {
            list.add(Pair.of(EquipmentSlot.LEGS, CraftItemStack.asNMSCopy(equipment.leggings())));
        }
        if (equipment.boots() != null) {
            list.add(Pair.of(EquipmentSlot.FEET, CraftItemStack.asNMSCopy(equipment.boots())));
        }
        if (equipment.mainHand() != null) {
            list.add(Pair.of(EquipmentSlot.MAINHAND, CraftItemStack.asNMSCopy(equipment.mainHand())));
        }
        return new ClientboundSetEquipmentPacket(entityId, list);
    }

    /**
     * 构造 NPC 显示名 Component（性格前缀 MiniMessage + baseName）。
     *
     * <p>前缀来自 lang 文件（{@code personality-prefix-{key}}，含尾部空格分隔，
     * MiniMessage 格式，抹茶绿 #a3b547）。总可见字符数超过 {@value #MC_NAME_LIMIT}
     * 时截断 baseName，前缀完整保留。normal 性格返回纯 baseName 字面 Component。</p>
     *
     * <p>解析失败时降级为 {@link #buildPlainDisplayName} 的字面 Component。</p>
     *
     * @param npc NPC
     * @return 显示名 Component
     */
    private Component buildDisplayName(SimNpc npc) {
        String baseName = npc.name();
        String prefixMini = messages.getPersonalityPrefix(npc.personality().name());
        if (prefixMini.isEmpty()) {
            // normal 或缺失前缀：返回纯 baseName
            return Component.text(baseName);
        }
        String truncatedBase = truncateBaseName(baseName, prefixMini);
        try {
            return miniMessage.deserialize(prefixMini + truncatedBase);
        } catch (RuntimeException ex) {
            // 前缀解析失败：降级为纯文本
            return Component.text(stripMiniTokens(prefixMini) + truncatedBase);
        }
    }

    /**
     * 构造 NPC 纯文本显示名（用于 GameProfile.name，受 16 字符上限）。
     *
     * <p>MiniMessage 标签剥离后的前缀 + 截断后的 baseName，总长 ≤ 16。
     * normal 性格返回纯 baseName。</p>
     *
     * @param npc NPC
     * @return 纯文本显示名
     */
    private String buildPlainDisplayName(SimNpc npc) {
        String baseName = npc.name();
        String prefixMini = messages.getPersonalityPrefix(npc.personality().name());
        if (prefixMini.isEmpty()) {
            return baseName;
        }
        return stripMiniTokens(prefixMini) + truncateBaseName(baseName, prefixMini);
    }

    /**
     * 截断 baseName 使前缀（纯文本）+ baseName 总长 ≤ {@value #MC_NAME_LIMIT}。
     * 前缀完整保留，仅截断 baseName。
     *
     * @param baseName   原始 baseName
     * @param prefixMini 前缀 MiniMessage 字符串
     * @return 截断后的 baseName
     */
    private String truncateBaseName(String baseName, String prefixMini) {
        int prefixLen = stripMiniTokens(prefixMini).length();
        int maxBaseLen = Math.max(0, MC_NAME_LIMIT - prefixLen);
        return baseName.length() > maxBaseLen ? baseName.substring(0, maxBaseLen) : baseName;
    }

    /**
     * 剥离 MiniMessage 标签，返回纯可见文本。
     *
     * <p>解析 + 纯文本序列化两步：先 {@link MiniMessage#deserialize} 解析为 Component，
     * 再 {@link PlainTextComponentSerializer#serialize} 输出纯文本。解析失败时降级为
     * 正则去除 {@code <...>} 标签（覆盖简单颜色/装饰标签，前缀场景足够）。</p>
     *
     * @param input MiniMessage 字符串
     * @return 纯文本
     */
    private String stripMiniTokens(String input) {
        try {
            return plainText.serialize(miniMessage.deserialize(input));
        } catch (RuntimeException ex) {
            return input.replaceAll("<[^>]+>", "");
        }
    }

    /**
     * 构造自定义名标签 metadata 包（DATA_CUSTOM_NAME + DATA_CUSTOM_NAME_VISIBLE）。
     *
     * <p>同时设置自定义名（含性格前缀 MiniMessage 颜色）和可见性为 true，
     * 使 NPC 头顶显示带颜色的性格前缀。CustomName 在玩家实体上的支持依赖客户端版本，
     * GameProfile.name 的纯文本前缀作为降级显示。</p>
     *
     * @param entityId    NPC entityId
     * @param displayName 显示名 Component
     * @return metadata 包
     */
    private ClientboundSetEntityDataPacket createCustomNamePacket(int entityId, Component displayName) {
        net.minecraft.network.chat.Component nmsName = PaperAdventure.asVanilla(displayName);
        SynchedEntityData.DataValue<Optional<net.minecraft.network.chat.Component>> name = new SynchedEntityData.DataValue<>(
                customNameId, EntityDataSerializers.OPTIONAL_COMPONENT, Optional.of(nmsName));
        SynchedEntityData.DataValue<Boolean> visible = new SynchedEntityData.DataValue<>(
                customNameVisibleId, EntityDataSerializers.BOOLEAN, true);
        return new ClientboundSetEntityDataPacket(entityId, List.of(name, visible));
    }

    /**
     * 反射读取指定 {@link EntityDataAccessor} 的内部 id。
     *
     * <p>accessor 本身在 Paper 26.1+ 经 {@code paper.at} 已 public，但其 {@code id} 字段为
     * 包级私有，需反射访问。构造时执行一次，失败返回 -1（降级：跳过对应 metadata）。</p>
     *
     * @param accessor entity data accessor
     * @return entity data id；反射失败返回 -1
     */
    private static int resolveEntityDataId(EntityDataAccessor<?> accessor) {
        try {
            Field idField = EntityDataAccessor.class.getDeclaredField("id");
            idField.setAccessible(true);
            return idField.getInt(accessor);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /**
     * 反射读取指定类的静态 {@link EntityDataAccessor} 字段并解析其内部 id。
     *
     * <p>用于 {@code Entity.DATA_CUSTOM_NAME} 等包级/私有访问控制的 accessor 字段 ——
     * Paper 26.1+ 仅对部分字段（如 {@code Avatar.DATA_PLAYER_MODE_CUSTOMISATION}）经
     * access transformer 提升 public，其余仍需反射按名获取。失败返回 -1（降级）。</p>
     *
     * @param holderClass accessor 字段所在类
     * @param fieldName   accessor 字段名
     * @return entity data id；反射失败返回 -1
     */
    private static int resolveEntityDataIdByName(Class<?> holderClass, String fieldName) {
        try {
            Field field = holderClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
            return resolveEntityDataId(accessor);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /**
     * 构造头部旋转包。
     *
     * <p>Paper 26.1+ 的 {@link ClientboundRotateHeadPacket} 仅有 {@code (Entity, byte)}
     * 公共构造器，但发包 NPC 无真实服务端实体；故通过 {@code STREAM_CODEC} +
     * {@link FriendlyByteBuf} 构造，模拟网络层解码流程。字节序与 NMS 内部
     * {@code write()} 一致：{@code VarInt(entityId) + Byte(yaw)}。</p>
     *
     * @param entityId 实体 ID
     * @param yaw      头部 yaw（度）
     * @return 头部旋转包
     */
    private static ClientboundRotateHeadPacket createHeadRotatePacket(int entityId, float yaw) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            buf.writeByte(toAngle(yaw));
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    private void sendPacket(Player player, Packet<?> packet) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }
        ServerPlayer serverPlayer = craftPlayer.getHandle();
        serverPlayer.connection.send(packet);
    }

    private Collection<Player> getNearbyPlayers(Location loc, double radius) {
        if (loc.getWorld() == null) {
            return List.of();
        }
        return loc.getWorld().getNearbyPlayers(loc, radius);
    }

    /**
     * 将角度（度）转换为 Minecraft 定点角度（byte）。
     *
     * @param degrees 角度（度）
     * @return 定点角度
     */
    private static byte toAngle(float degrees) {
        return (byte) (degrees * 256.0f / 360.0f);
    }

    /**
     * 计算两个角度之间的最短差值（度），结果范围 {@code [0, 180]}。
     *
     * @param a 角度 a
     * @param b 角度 b
     * @return 最短角度差
     */
    private static float angleDiff(float a, float b) {
        float diff = Math.abs(a - b) % 360;
        return diff > 180 ? 360 - diff : diff;
    }
}
