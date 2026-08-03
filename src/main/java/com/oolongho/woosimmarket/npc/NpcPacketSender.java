package com.oolongho.woosimmarket.npc;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.npc.adapter.NmsAdapter;
import com.oolongho.woosimmarket.npc.adapter.NmsAdapterFactory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
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

import java.lang.reflect.Constructor;
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
 * <p>跨版本兼容：spawn 与 teleport 包构造委托给 {@link NmsAdapter}，
 * 由 {@link NmsAdapterFactory} 运行时按服务端版本选择 Legacy（1.21.0/1）或
 * Modern（1.21.2+ 及 26.1+）实现。{@code DATA_PLAYER_MODE_CUSTOMISATION} 的
 * accessor 在 26.1+ 位于 {@code Avatar}、1.21.x 位于 {@code player.Player}，
 * 通过反射按类名依次尝试避免编译期硬依赖。</p>
 *
 * <p>包序列（spawn）：
 * <ol>
 *   <li>{@link ClientboundPlayerInfoUpdatePacket}（ADD_PLAYER）— 添加到 TAB 列表</li>
 *   <li>{@code ClientboundAddEntityPacket}（由 {@link NmsAdapter#createSpawnPacket} 构造）— 生成玩家实体</li>
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
 * 降级为绝对位置传送包（由 {@link NmsAdapter#createTeleportPacket} 构造）。</p>
 *
 * @author oolongho
 */
public class NpcPacketSender {

    /** 移动包最大相对位移（方块），超过则降级为 Teleport。 */
    private static final double MAX_MOVE_DELTA = 7.0;

    /** Minecraft 玩家名上限（GameProfile.name 长度限制）。 */
    private static final int MC_NAME_LIMIT = 16;

    /**
     * {@code ClientboundPlayerInfoUpdatePacket.Entry} 构造器参数数（运行期反射探测）。
     *
     * <p>跨版本兼容：1.21.x 的 Entry record 构造器签名有 3 种变体，编译期 dev bundle
     * (1.21.11) 仅可见 9 参数版本，故 7/8 参数变体必须经反射调用。</p>
     * <ul>
     *   <li>1.21.0/1/2：7 参数（无 showHat、无 listOrder）—— {@code (UUID, GameProfile,
     *       boolean listed, int latency, GameType, Component, RemoteChatSession.Data)}</li>
     *   <li>1.21.3：8 参数（新增 listOrder，位于 chatSession 之前）—— 上述 + {@code int listOrder}</li>
     *   <li>1.21.4+：9 参数（新增 showHat，位于 listOrder 之前）—— 上述 + {@code boolean showHat}</li>
     * </ul>
     *
     * <p>1.21.2 在 mappings.dev 无独立页面，但 Action enum 历史显示 {@code UPDATE_LIST_ORDER}
     * 在 1.21.3 才加入，故 1.21.2 极可能仍是 7 参数。本字段运行期探测，无需硬编码版本边界。</p>
     */
    private static final int ENTRY_PARAM_COUNT = detectEntryParamCount();

    /**
     * 7/8 参数 Entry 构造器的反射缓存（用于 1.21.0~1.21.3）。
     *
     * <p>9 参数（1.21.4+）走 fast-path 直接 {@code new}，无需此字段（{@code null}）。
     * 反射 Constructor 在首次使用后由 JIT 优化，与直接 {@code new} 差距约 20~50ns/spawn，
     * NPC spawn 频率（10~100/s）下可忽略。</p>
     */
    private static final Constructor<?> ENTRY_REFLECT_CONSTRUCTOR = ENTRY_PARAM_COUNT == 9
            ? null
            : resolveEntryConstructor(ENTRY_PARAM_COUNT);

    private static int detectEntryParamCount() {
        int maxParams = 0;
        for (Constructor<?> c : ClientboundPlayerInfoUpdatePacket.Entry.class.getConstructors()) {
            // 跳过 (ServerPlayer) 单参数构造器，取参数数最多的 public 构造器
            if (c.getParameterCount() > maxParams) {
                maxParams = c.getParameterCount();
            }
        }
        if (maxParams < 7) {
            throw new IllegalStateException(
                    "ClientboundPlayerInfoUpdatePacket.Entry: no suitable constructor (max params=" + maxParams + ")");
        }
        return maxParams;
    }

    private static Constructor<?> resolveEntryConstructor(int paramCount) {
        for (Constructor<?> c : ClientboundPlayerInfoUpdatePacket.Entry.class.getConstructors()) {
            if (c.getParameterCount() == paramCount) {
                return c;
            }
        }
        throw new IllegalStateException(
                "ClientboundPlayerInfoUpdatePacket.Entry: no constructor with " + paramCount + " params");
    }

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

    /** NMS 适配器，隔离 spawn/teleport 包构造的跨版本差异（1.21.0/1 vs 1.21.2+ 及 26.1+）。 */
    private final NmsAdapter nmsAdapter;

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
        this.nmsAdapter = NmsAdapterFactory.getInstance();
        // 26.1+：Avatar.DATA_PLAYER_MODE_CUSTOMISATION；1.21.x：player.Player.DATA_PLAYER_MODE_CUSTOMISATION
        // 编译期统一用 1.21.x dev bundle，Avatar 类不存在于符号表，故通过反射按类名查找避免硬引用。
        // 依次尝试 Avatar → Player，命中第一个即返回；全部失败返回 -1 降级为无 skin 外层 metadata。
        this.playerModeCustomisationId = resolveEntityDataIdByClassNames(
                "net.minecraft.world.entity.Avatar",
                "net.minecraft.world.entity.player.Player",
                "DATA_PLAYER_MODE_CUSTOMISATION");
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

        // 2. 生成玩家实体（spawn 包构造委托给 NmsAdapter，跨版本签名差异隔离）
        //    headYaw 传 0.0：与原实现一致，由后续 ClientboundRotateHeadPacket 单独修正，
        //    避免在此处设置后又被 spawn 内的头部旋转包覆盖（spawn 时 lastHeadYaws 未更新）
        sendPacket(player, nmsAdapter.createSpawnPacket(
                npc.entityId(), npc.uuid(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw(),
                EntityType.PLAYER, 0,
                Vec3.ZERO, 0.0f));

        // 3. 设置 skin 外层 metadata（反射失败时跳过，降级为无外层，NPC 仍可正常工作）
        if (playerModeCustomisationId >= 0) {
            sendPacket(player, createSkinPartsPacket(npc.entityId(), configLoader.getNpcSkinParts()));
        }

        // 3.5 设置自定义名标签（性格前缀 + baseName，含 MiniMessage 颜色）
        //    反射失败或前缀为空时跳过；GameProfile.name 已含纯文本前缀作为降级显示
        String prefixMini = messages.getPersonalityPrefix(npc.personality().name());
        if (customNameId >= 0 && customNameVisibleId >= 0 && !prefixMini.isEmpty()) {
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
        ClientboundPlayerInfoUpdatePacket.Entry entry =
                createInfoEntry(profile, PaperAdventure.asVanilla(displayName));
        return new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(entry));
    }

    /**
     * 构造 {@code ClientboundPlayerInfoUpdatePacket.Entry}，跨版本兼容 1.21.x 三种构造器变体。
     *
     * <p>1.21.4+（9 参数）走 fast-path 直接 {@code new}；1.21.0~1.21.3（7/8 参数）经缓存的
     * 反射 Constructor 调用。参数顺序与各版本 record 字段顺序一致（见 {@link #ENTRY_PARAM_COUNT}
     * 的版本对照表）。NPC 静态：listed=false、latency=0、gameMode=SURVIVAL、chatSession=null、
     * showHat=false、listOrder=0。</p>
     *
     * @param profile     NPC GameProfile（含 skin textures）
     * @param displayName NMS 显示名 Component
     * @return Entry 实例
     */
    private static ClientboundPlayerInfoUpdatePacket.Entry createInfoEntry(
            GameProfile profile, net.minecraft.network.chat.Component displayName) {
        if (ENTRY_PARAM_COUNT == 9) {
            // 1.21.4+ fast-path：编译期 1.21.11 dev bundle 直接匹配，类型安全
            return new ClientboundPlayerInfoUpdatePacket.Entry(
                    profile.id(), profile, false, 0, GameType.SURVIVAL,
                    displayName, false, 0, null);
        }
        // 1.21.0~1.21.3：反射调用（编译期无 7/8 参数符号）
        Object[] args = switch (ENTRY_PARAM_COUNT) {
            case 8 -> new Object[]{  // 1.21.3：listOrder 在 chatSession 之前
                    profile.id(), profile, false, 0, GameType.SURVIVAL, displayName, 0, null};
            case 7 -> new Object[]{  // 1.21.0/1/2：无 listOrder、无 showHat
                    profile.id(), profile, false, 0, GameType.SURVIVAL, displayName, null};
            default -> throw new IllegalStateException(
                    "Unexpected Entry constructor param count: " + ENTRY_PARAM_COUNT);
        };
        try {
            return (ClientboundPlayerInfoUpdatePacket.Entry) ENTRY_REFLECT_CONSTRUCTOR.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 构造绝对位置传送包。委托给 {@link NmsAdapter}，由其按运行时服务端版本选择
     * {@code PositionMoveRotation}（1.21.2+ 及 26.1+）或 {@code FriendlyByteBuf} +
     * {@code STREAM_CODEC.decode}（1.21.0/1）构造方式。
     *
     * @param npc NPC
     * @return 传送包
     */
    private Packet<?> createTeleportPacket(SimNpc npc) {
        Location loc = npc.location();
        return nmsAdapter.createTeleportPacket(
                npc.entityId(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                Set.of(), false);
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
     * 时截断 baseName，前缀完整保留。前缀为空时返回纯 baseName 字面 Component（normal 默认前缀为空）。</p>
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
            // 前缀为空（normal 默认或缺失前缀）：返回纯 baseName
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
     * 前缀为空时返回纯 baseName（normal 默认前缀为空）。</p>
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
     * 反射读取指定类的静态 {@link EntityDataAccessor} 字段并解析其内部 id（按类名查找）。
     *
     * <p>用于编译期不存在于符号表、运行期才加载的类（如 26.1+ 的
     * {@code net.minecraft.world.entity.Avatar}，在 1.21.x dev bundle 编译时不可直接引用）。
     * 通过 {@link Class#forName} 运行期加载，避免编译期硬依赖。失败返回 -1（降级）。</p>
     *
     * @param className accessor 字段所在类的全限定名
     * @param fieldName  accessor 字段名
     * @return entity data id；类未找到或反射失败返回 -1
     */
    private static int resolveEntityDataIdByClassName(String className, String fieldName) {
        try {
            Class<?> holderClass = Class.forName(className);
            return resolveEntityDataIdByName(holderClass, fieldName);
        } catch (ClassNotFoundException e) {
            return -1;
        }
    }

    /**
     * 反射读取多个候选类的静态 {@link EntityDataAccessor} 字段，返回第一个命中的 id。
     *
     * <p>用于跨版本兼容：26.1+ 字段位于 {@code Avatar}，1.21.x 字段位于
     * {@code player.Player}，运行期按类名依次尝试，命中即返回。
     * 全部失败返回 -1（降级）。</p>
     *
     * @param classNames 候选类全限定名数组（按优先级排序）
     * @param fieldName  accessor 字段名（在所有候选类中一致）
     * @return 第一个命中类的 entity data id；全部失败返回 -1
     */
    private static int resolveEntityDataIdByClassNames(String[] classNames, String fieldName) {
        for (String className : classNames) {
            int id = resolveEntityDataIdByClassName(className, fieldName);
            if (id >= 0) {
                return id;
            }
        }
        return -1;
    }

    /**
     * 两参数便捷重载：等价于 {@code resolveEntityDataIdByClassNames(new String[]{first, second}, fieldName)}。
     *
     * @param first    第一候选类全限定名
     * @param second   第二候选类全限定名
     * @param fieldName accessor 字段名
     * @return 第一个命中类的 entity data id；全部失败返回 -1
     */
    private static int resolveEntityDataIdByClassNames(String first, String second, String fieldName) {
        return resolveEntityDataIdByClassNames(new String[]{first, second}, fieldName);
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
