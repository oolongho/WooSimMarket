package com.oolongho.woosimmarket.npc.adapter.v1_21_0;

import com.oolongho.woosimmarket.npc.adapter.NmsAdapter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * MC 1.21.0/1 的 NMS 适配器。
 *
 * <p>1.21.0/1 缺少 {@code PositionMoveRotation} 类，{@code ClientboundTeleportEntityPacket}
 * 必须通过 {@code FriendlyByteBuf} 模拟网络层解码流程构造。
 * 字节序与 NMS 内部 {@code write()} 一致：
 * {@code VarInt(entityId) + Double(x/y/z) + Byte(yaw) + Byte(pitch) + Boolean(onGround)}。</p>
 *
 * <p>spawn 包构造签名与 1.21.2+ 一致（11 参数含 headYaw），直接调用。</p>
 *
 * @author oolongho
 */
public final class LegacyNmsAdapter implements NmsAdapter {

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
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeByte((byte) (yaw * 256.0F / 360.0F));
            buf.writeByte((byte) (pitch * 256.0F / 360.0F));
            buf.writeBoolean(onGround);
            return ClientboundTeleportEntityPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
