package com.oolongho.woosimmarket.npc.adapter;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * NMS 数据包构造适配器。
 *
 * <p>隔离 Minecraft 跨版本 NMS 差异，使 {@code NpcPacketSender} 可在单 jar 中兼容
 * Paper/Folia/Spigot 1.21+~26.1+。仅承担版本相关的 spawn/teleport 构造差异；
 * metadata、装备、信息包等构造方式跨版本一致，仍由 {@code NpcPacketSender} 直接处理。</p>
 *
 * <ul>
 *   <li>{@link com.oolongho.woosimmarket.npc.adapter.v1_21_0.LegacyNmsAdapter LegacyNmsAdapter}：1.21.0/1
 *       —— {@code ClientboundTeleportEntityPacket} 通过 {@code FriendlyByteBuf} +
 *       {@code STREAM_CODEC.decode} 构造（无 {@code PositionMoveRotation} 类）。</li>
 *   <li>{@link com.oolongho.woosimmarket.npc.adapter.v1_21_2.ModernNmsAdapter ModernNmsAdapter}：1.21.2+
 *       及 Spigot 26.1+ —— {@code ClientboundTeleportEntityPacket} 通过 {@code PositionMoveRotation} 构造。</li>
 * </ul>
 *
 * <p>spawn 包（{@code ClientboundAddEntityPacket} 11 参数构造含 headYaw）在 1.21.0/1 与
 * 1.21.2+ 签名一致，但为统一调用路径与未来扩展空间，仍经由 adapter 路由。</p>
 *
 * @author oolongho
 */
public interface NmsAdapter {

    /**
     * 构造 NPC 生成实体包（{@code ClientboundAddEntityPacket}）。
     *
     * @param entityId 实体 ID
     * @param uuid     实体 UUID
     * @param x        生成位置 X
     * @param y        生成位置 Y
     * @param z        生成位置 Z
     * @param pitch    pitch（度）
     * @param yaw      yaw（度）
     * @param type     实体类型（玩家为 {@link EntityType#PLAYER}）
     * @param data     实体附加数据（玩家为 0）
     * @param delta    速度向量（NPC 静态生成为 {@link Vec3#ZERO}）
     * @param headYaw  头部 yaw（度）
     * @return 生成包
     */
    Packet<?> createSpawnPacket(int entityId, UUID uuid, double x, double y, double z,
                                float pitch, float yaw, EntityType<?> type, int data,
                                Vec3 delta, float headYaw);

    /**
     * 构造 NPC 绝对位置传送包（{@code ClientboundTeleportEntityPacket}）。
     *
     * @param entityId  实体 ID
     * @param x         目标位置 X
     * @param y         目标位置 Y
     * @param z         目标位置 Z
     * @param yaw       yaw（度）
     * @param pitch     pitch（度）
     * @param relatives 相对位移枚举集合（NPC 通常为空集）
     * @param onGround  是否在地面
     * @return 传送包
     */
    Packet<?> createTeleportPacket(int entityId, double x, double y, double z,
                                   float yaw, float pitch,
                                   Set<Relative> relatives, boolean onGround);
}
