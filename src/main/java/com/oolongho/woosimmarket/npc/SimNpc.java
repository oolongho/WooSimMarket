package com.oolongho.woosimmarket.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 NPC 顾客（纯数据模型 + tick 移动逻辑）。
 *
 * <p>不创建任何服务端实体，位置由 {@link #tick()} 计算后通过
 * {@link NpcPacketSender} 发包同步给客户端。</p>
 *
 * <p>移动模型：XZ 平面直线插值，遇 1 格高方块跳跃（Y+1），不做重力模拟
 * （假设商店区平坦）。卡住判定：每 {@code stuckThresholdSeconds} 秒检查一次 XZ 位移，
 * 不足 {@code stuckThresholdDistance} 则判定卡住。</p>
 *
 * @author oolongho
 */
public class SimNpc {

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(100000);

    /** NPC 皮肤数据（Ashcon textures value + signature）。 */
    public record SkinData(String value, String signature) {}

    /** tick 结果。 */
    public enum TickResult {
        /** 正常移动了一步 */
        MOVING,
        /** 到达目标货架 */
        REACHED,
        /** 卡住（超时未移动足够距离） */
        STUCK,
        /** 已到达离开目标点，应销毁 */
        DESPAWN,
        /** 非移动状态（等待处理到达事件） */
        IDLE
    }

    /** NPC 状态。 */
    public enum State {
        /** 移动到货架 */
        MOVING,
        /** 到达货架，等待购买判定 */
        REACHED,
        /** 购买完成，离开中 */
        LEAVING
    }

    // 不可变字段
    private final UUID uuid;
    private final int entityId;
    private final String name;
    private final SkinData skin;
    private final String shopId;
    private final String shelfId;
    private final long spawnTime;

    // 可变字段
    private Location location;
    private Location target;
    private State state;
    private Location lastStuckCheckLoc;
    private long lastStuckCheckTime;

    // 移动参数
    private final double speed;
    private final double reachDistance;
    private final int stuckThresholdSeconds;
    private final double stuckThresholdDistance;

    public SimNpc(UUID uuid, String name, SkinData skin, String shopId, String shelfId,
                  Location spawnLocation, Location target,
                  double speed, double reachDistance,
                  int stuckThresholdSeconds, double stuckThresholdDistance) {
        this.uuid = uuid;
        this.entityId = ENTITY_ID_COUNTER.incrementAndGet();
        this.name = name;
        this.skin = skin;
        this.shopId = shopId;
        this.shelfId = shelfId;
        this.spawnTime = System.currentTimeMillis();
        this.location = spawnLocation.clone();
        this.target = target.clone();
        this.state = State.MOVING;
        this.lastStuckCheckLoc = spawnLocation.clone();
        this.lastStuckCheckTime = this.spawnTime;
        this.speed = speed;
        this.reachDistance = reachDistance;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.stuckThresholdDistance = stuckThresholdDistance;
    }

    /**
     * 每 tick 调用：计算下一步位置并更新内部状态。
     *
     * <p>仅在 {@link State#MOVING} 或 {@link State#LEAVING} 状态下执行移动。
     * 到达目标后状态变为 {@link State#REACHED}，调用方应处理购买判定后
     * 调用 {@link #startLeaving(Location)} 切换到离开状态。</p>
     *
     * @return tick 结果
     */
    public TickResult tick() {
        if (state != State.MOVING && state != State.LEAVING) {
            return TickResult.IDLE;
        }

        double dx = target.getX() - location.getX();
        double dz = target.getZ() - location.getZ();
        double distSqXZ = dx * dx + dz * dz;

        // 到达判定（仅 MOVING 状态）
        if (state == State.MOVING && distSqXZ <= reachDistance * reachDistance) {
            state = State.REACHED;
            return TickResult.REACHED;
        }

        // 离开完成判定（LEAVING 状态：到达离开目标点即销毁）
        if (state == State.LEAVING && distSqXZ <= reachDistance * reachDistance) {
            return TickResult.DESPAWN;
        }

        // 归一化方向并移动（此处 distSqXZ > reachDistance^2，distXZ 必 > 0）
        double distXZ = Math.sqrt(distSqXZ);
        double moveX = (dx / distXZ) * speed;
        double moveZ = (dz / distXZ) * speed;
        double newX = location.getX() + moveX;
        double newZ = location.getZ() + moveZ;
        double newY = location.getY();

        // 跳跃判定：前方有非空气方块且上方为空气 → Y+1
        World world = location.getWorld();
        if (world != null) {
            Block front = world.getBlockAt(
                    (int) Math.floor(newX),
                    (int) Math.floor(location.getY()),
                    (int) Math.floor(newZ));
            if (!front.getType().isAir() && front.getRelative(BlockFace.UP).getType().isAir()) {
                newY = location.getY() + 1.0;
            }
        }

        // 更新 yaw 朝向移动方向
        float yaw = (float) Math.toDegrees(Math.atan2(-moveX, moveZ));

        location.setX(newX);
        location.setY(newY);
        location.setZ(newZ);
        location.setYaw(yaw);

        // 卡住检测
        long now = System.currentTimeMillis();
        if (now - lastStuckCheckTime >= stuckThresholdSeconds * 1000L) {
            double stuckDx = location.getX() - lastStuckCheckLoc.getX();
            double stuckDz = location.getZ() - lastStuckCheckLoc.getZ();
            if (stuckDx * stuckDx + stuckDz * stuckDz < stuckThresholdDistance * stuckThresholdDistance) {
                return TickResult.STUCK;
            }
            lastStuckCheckLoc = location.clone();
            lastStuckCheckTime = now;
        }

        return TickResult.MOVING;
    }

    /**
     * 切换到离开状态，朝远离商店的方向移动。
     *
     * @param leaveTarget 离开目标点
     */
    public void startLeaving(Location leaveTarget) {
        this.target = leaveTarget;
        this.state = State.LEAVING;
    }

    // ===== Getter =====

    public UUID uuid() {
        return uuid;
    }

    public int entityId() {
        return entityId;
    }

    public String name() {
        return name;
    }

    public SkinData skin() {
        return skin;
    }

    public String shopId() {
        return shopId;
    }

    public String shelfId() {
        return shelfId;
    }

    public Location location() {
        return location;
    }

    public Location target() {
        return target;
    }

    public State state() {
        return state;
    }

    public long spawnTime() {
        return spawnTime;
    }
}
