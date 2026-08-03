package com.oolongho.woosimmarket.npc.adapter.v1_21_2;

import com.oolongho.woosimmarket.npc.adapter.NmsAdapter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * MC 1.21.2+ 及 Spigot 26.1+ 的 NMS 适配器。
 *
 * <p>1.21.2+ 起引入 {@code PositionMoveRotation}，{@code ClientboundTeleportEntityPacket}
 * 通过其公共构造器构造。spawn 包与 1.21.0/1 共享 11 参数构造签名。</p>
 *
 * @author oolongho
 */
public final class ModernNmsAdapter implements NmsAdapter {

    @Override
    public Packet<?> createSpawnPacket(int entityId, UUID uuid, double x, double y, double z,
                                       float pitch, float yaw, EntityType<?> type, int data,
                                       Vec3 delta, float headYaw) {
        return new ClientboundAddEntityPacket(
                entityId, uuid, x, y, z, pitch, yaw, type, data, delta, headYaw);
    }

    @Override
    public Packet<?> createTeleportPacket(int entityId, double x, double y, double z,
                                          float yaw, float pitch,
                                          Set<Relative> relatives, boolean onGround) {
        return new ClientboundTeleportEntityPacket(
                entityId,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch),
                relatives,
                onGround);
    }
}
