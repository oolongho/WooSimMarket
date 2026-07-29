package com.oolongho.woosimmarket.npc;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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

    /** 每个 NPC 上次发送给各客户端的位置（entityId → playerUuid → location），用于计算相对位移。 */
    private final Map<Integer, Map<UUID, Location>> lastSentByNpc = new ConcurrentHashMap<>();

    /** NPC entityId → 已收到 spawn 包的玩家 UUID 集合（用于 auto-spawn 与精确移除）。 */
    private final Map<Integer, Set<UUID>> playersByNpc = new ConcurrentHashMap<>();

    /** NPC entityId → 上次发送的 head yaw（用于判断是否需要更新头部朝向）。 */
    private final Map<Integer, Float> lastHeadYaws = new ConcurrentHashMap<>();

    public NpcPacketSender() {
    }

    /**
     * 向单个玩家发送 NPC 生成包，并记录追踪关系。
     *
     * @param player 目标玩家
     * @param npc    NPC
     */
    public void spawn(Player player, SimNpc npc) {
        GameProfile profile = createProfile(npc);
        Location loc = npc.location();

        // 1. 添加到 TAB 列表
        sendPacket(player, createInfoAddPacket(profile));

        // 2. 生成玩家实体（26.1+ 使用 ClientboundAddEntityPacket）
        sendPacket(player, new ClientboundAddEntityPacket(
                npc.entityId(), npc.uuid(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw(),
                EntityType.PLAYER, 0,
                Vec3.ZERO, 0.0));

        // 3. 从 TAB 列表隐藏
        sendPacket(player, ClientboundPlayerInfoUpdatePacket.updateListed(npc.uuid(), false));

        // 4. 设置头部朝向与身体一致（spawn 时强制发送一次，避免客户端头部朝默认方向）
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
        SimNpc.SkinData skin = npc.skin();
        if (skin != null && skin.value() != null && !skin.value().isEmpty()) {
            Multimap<String, Property> multimap = HashMultimap.create();
            multimap.put("textures", new Property("textures", skin.value(), skin.signature()));
            return new GameProfile(npc.uuid(), npc.name(), new PropertyMap(multimap));
        }
        return new GameProfile(npc.uuid(), npc.name());
    }

    private ClientboundPlayerInfoUpdatePacket createInfoAddPacket(GameProfile profile) {
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.id(), profile, false, 0, GameType.SURVIVAL,
                null, false, 0, null);
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
