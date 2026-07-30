package com.oolongho.woosimmarket.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 NPC 顾客（纯数据模型 + tick 移动逻辑）。
 *
 * <p>不创建任何服务端实体，位置由 {@link #tick()} 计算后通过
 * {@link NpcPacketSender} 发包同步给客户端。</p>
 *
 * <p>移动模型：路径点跟随 + 重力下坠 + 台阶攀爬。
 * <ul>
 *   <li>{@link State#WAITING_FOR_PATH}：NPC 生成后等待异步 A* 寻路完成，
 *       tick 返回 {@link TickResult#IDLE}（不移动不发包）</li>
 *   <li>{@link State#MOVING}：沿 {@link #path} 路径点序列移动，每个路径点到达后
 *       切换下一个；全部走完返回 {@link TickResult#REACHED}（不切换 state，
 *       由 NpcManager 处理购买判定后调用 {@link #startLeaving()}）</li>
 *   <li>{@link State#LEAVING}：使用逆向 {@link #path} 返回，
 *       全部走完返回 {@link TickResult#DESPAWN}</li>
 * </ul></p>
 *
 * <p>Y 坐标处理（每 tick 移动后）：
 * <ul>
 *   <li>脚位固体（穿模/上台阶）→ 上升到方块顶（台阶攀爬）</li>
 *   <li>脚位空气 + 下方空气（悬空/下台阶）→ Y -= 0.3（重力下坠）</li>
 *   <li>脚位空气 + 下方固体（平走）→ 吸附到方块顶（偏差 > 0.05 时才吸附，
 *       避免每 tick 浮点误差触发抖动）</li>
 * </ul></p>
 *
 * <p>卡住判定：每 {@code stuckThresholdSeconds} 秒检查一次 XZ 位移，
 * 不足 {@code stuckThresholdDistance} 则判定卡住。</p>
 *
 * @author oolongho
 */
public class SimNpc {

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(100000);

    /** 重力下坠量（格/tick）。 */
    private static final double GRAVITY_FALL = 0.3;
    /** 吸附阈值（避免浮点误差导致每 tick 重复吸附）。 */
    private static final double SNAP_THRESHOLD = 0.05;

    /** NPC 皮肤数据（Ashcon textures value + signature）。 */
    public record SkinData(String value, String signature) {}

    /** NPC 装备（4 部位，可为 null 表示不穿戴；头盔始终为空保留头部皮肤）。 */
    public record Equipment(ItemStack chestplate, ItemStack leggings,
                             ItemStack boots, ItemStack mainHand) {
        /** 全空装备（equipment 禁用时使用）。 */
        public static final Equipment EMPTY = new Equipment(null, null, null, null);

        /** 是否所有部位都为空。 */
        public boolean isEmpty() {
            return chestplate == null && leggings == null && boots == null && mainHand == null;
        }
    }

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
        /** 非移动状态（等待路径计算） */
        IDLE
    }

    /** NPC 状态。 */
    public enum State {
        /** 等待异步 A* 寻路完成（NPC 生成后初始状态，不发包不移动） */
        WAITING_FOR_PATH,
        /** 沿路径移动到货架 */
        MOVING,
        /** 购买完成，沿逆向路径离开中 */
        LEAVING
    }

    // 不可变字段
    private final UUID uuid;
    private final int entityId;
    private final String name;
    private final SkinData skin;
    private final Equipment equipment;
    private final String shopId;
    private final String shelfId;
    private final long spawnTime;

    // 可变字段
    private Location location;
    private List<Location> path;
    private int waypointIndex;
    private State state;
    private Location lastStuckCheckLoc;
    private long lastStuckCheckTime;

    // 移动参数
    private final double speed;
    private final double reachDistance;
    private final int stuckThresholdSeconds;
    private final double stuckThresholdDistance;

    public SimNpc(UUID uuid, String name, SkinData skin, Equipment equipment, String shopId, String shelfId,
                  Location spawnLocation,
                  double speed, double reachDistance,
                  int stuckThresholdSeconds, double stuckThresholdDistance) {
        this.uuid = uuid;
        this.entityId = ENTITY_ID_COUNTER.incrementAndGet();
        this.name = name;
        this.skin = skin;
        this.equipment = equipment;
        this.shopId = shopId;
        this.shelfId = shelfId;
        this.spawnTime = System.currentTimeMillis();
        this.location = spawnLocation.clone();
        this.path = null;
        this.waypointIndex = 0;
        this.state = State.WAITING_FOR_PATH;
        this.lastStuckCheckLoc = spawnLocation.clone();
        this.lastStuckCheckTime = this.spawnTime;
        this.speed = speed;
        this.reachDistance = reachDistance;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.stuckThresholdDistance = stuckThresholdDistance;
    }

    /**
     * 设置 A* 寻路计算完成的路径，并切换到 MOVING 状态。
     *
     * <p>由 NpcManager 在寻路回调（主线程）中调用。调用此方法后 NPC 才会开始移动，
     * NpcManager 应紧随其后调用 {@link NpcPacketSender#spawnToNearby} 发送生成包。</p>
     *
     * <p>同时重置卡住检测计时器：路径就绪前的等待时间不应计入卡住判定，
     * 避免长寻路导致首 tick 误判 STUCK。</p>
     *
     * @param path 路径点列表（不含起点，含终点；方块中心坐标 x+0.5, y, z+0.5）
     */
    public void setPath(List<Location> path) {
        this.path = path;
        this.waypointIndex = 0;
        this.state = State.MOVING;
        this.lastStuckCheckLoc = location.clone();
        this.lastStuckCheckTime = System.currentTimeMillis();
    }

    /**
     * 每 tick 调用：计算下一步位置并更新内部状态。
     *
     * <p>状态机：</p>
     * <ul>
     *   <li>{@link State#WAITING_FOR_PATH} → 返回 {@link TickResult#IDLE}（不移动不发包）</li>
     *   <li>{@link State#MOVING}：沿 {@link #path} 移动，全部走完返回
     *       {@link TickResult#REACHED}（不切换 state，由 NpcManager 处理）</li>
     *   <li>{@link State#LEAVING}：沿逆向 {@link #path} 移动，全部走完返回
     *       {@link TickResult#DESPAWN}</li>
     * </ul>
     *
     * <p>到达中间路径点（XZ 距离 ≤ {@code reachDistance}）时 {@code waypointIndex++}，
     * 若未全部走完则本 tick 继续朝下一路径点移动；若下一路径点也已在 reach 范围内
     * （极近路径点），本 tick 不移动，下 tick 处理，避免单 tick 跨多路径点。</p>
     *
     * @return tick 结果
     */
    public TickResult tick() {
        if (state != State.MOVING && state != State.LEAVING) {
            return TickResult.IDLE;
        }
        // 防御：path 未就绪（不应发生，setPath 之后才进入 MOVING/LEAVING）
        if (path == null || path.isEmpty()) {
            return TickResult.IDLE;
        }

        // 防御：waypointIndex 已越界（handleReached 异常未切换 state 时，重试 REACHED/DESPAWN）
        if (waypointIndex >= path.size()) {
            return (state == State.MOVING) ? TickResult.REACHED : TickResult.DESPAWN;
        }

        Location current = path.get(waypointIndex);
        double dx = current.getX() - location.getX();
        double dz = current.getZ() - location.getZ();
        double distSqXZ = dx * dx + dz * dz;

        // 到达当前路径点 → 切换下一路径点
        if (distSqXZ <= reachDistance * reachDistance) {
            waypointIndex++;
            if (waypointIndex >= path.size()) {
                // 全部走完：MOVING 返回 REACHED（不切换 state），LEAVING 返回 DESPAWN
                return (state == State.MOVING) ? TickResult.REACHED : TickResult.DESPAWN;
            }
            // 重新计算到下一路径点的方向
            current = path.get(waypointIndex);
            dx = current.getX() - location.getX();
            dz = current.getZ() - location.getZ();
            distSqXZ = dx * dx + dz * dz;
            // 极近路径点（已在 reach 范围内）：本 tick 不移动，下 tick 再处理
            if (distSqXZ <= reachDistance * reachDistance) {
                return TickResult.MOVING;
            }
        }

        // 归一化方向并移动 XZ（此时 distSqXZ > reachDistance^2，distXZ 必 > 0）
        double distXZ = Math.sqrt(distSqXZ);
        double moveX = (dx / distXZ) * speed;
        double moveZ = (dz / distXZ) * speed;
        double newX = location.getX() + moveX;
        double newZ = location.getZ() + moveZ;
        double newY = location.getY();

        // Y 坐标处理：重力下坠 + 台阶攀爬 + 方块顶吸附
        World world = location.getWorld();
        if (world != null) {
            int bx = (int) Math.floor(newX);
            int by = (int) Math.floor(newY);
            int bz = (int) Math.floor(newZ);
            Block feet = world.getBlockAt(bx, by, bz);
            Block below = world.getBlockAt(bx, by - 1, bz);

            if (feet.getType().isSolid()) {
                // 脚位固体（穿模/上台阶）→ 上升到脚位方块顶部
                newY = by + 1.0;
            } else if (below.getType().isAir()) {
                // 脚下空气（悬空/下台阶）→ 重力下坠
                newY = newY - GRAVITY_FALL;
            } else {
                // 脚下固体 → 吸附到方块顶（仅在偏差 > 阈值时才吸附，避免抖动）
                double top = (by - 1) + 1.0; // below.y + 1
                if (Math.abs(newY - top) > SNAP_THRESHOLD) {
                    newY = top;
                }
            }
        }

        // 更新 yaw 朝向当前移动方向（pitch 保持 0，平视）
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
     * 切换到离开状态：逆向原路径返回。
     *
     * <p>将 {@link #path} 反转（终点变起点），重置 {@link #waypointIndex} 为 0，
     * 状态切换为 {@link State#LEAVING}。由 NpcManager 在购买判定后调用。</p>
     */
    public void startLeaving() {
        if (path != null) {
            Collections.reverse(path);
        }
        this.waypointIndex = 0;
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

    public Equipment equipment() {
        return equipment;
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

    public State state() {
        return state;
    }

    public long spawnTime() {
        return spawnTime;
    }
}
