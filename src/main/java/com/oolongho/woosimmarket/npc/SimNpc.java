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
 *       由 NpcManager 调用 {@link #startDeliberation()} 进入徘徊）</li>
 *   <li>{@link State#DELIBERATING}：到达货架后原地站立，由 impatience 决定的
 *       次数/间隔多次判定；到点返回 {@link TickResult#ROLL_DUE} 交 NpcManager roll，
 *       命中或耗尽则由 NpcManager 调 {@link #startLingering()}</li>
 *   <li>{@link State#LINGER}：判定结束后原地停留，展示 BUY/GIVE_UP 结果文本，
 *       计时器倒数到 0 自动调 {@link #startLeaving()} 切换到 LEAVING</li>
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
 * <p>性格（{@link #personality}）：spawn 时由 {@link NpcManager} 调用
 * {@link PersonalityManager#random()} 加权随机分配，生命周期内不变，不持久化。
 * 本 spec（子系统 1）只存储数据，判别式留给后续子系统。</p>
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
        /** 非移动状态（等待路径计算 / 徘徊未到判定点） */
        IDLE,
        /** 徘徊判定到点，请求 NpcManager 执行一次 roll */
        ROLL_DUE
    }

    /** NPC 状态。 */
    public enum State {
        /** 等待异步 A* 寻路完成（NPC 生成后初始状态，不发包不移动） */
        WAITING_FOR_PATH,
        /** 沿路径移动到货架 */
        MOVING,
        /** 到达货架，原地站立思考中（多次判定 + 计时器驱动） */
        DELIBERATING,
        /** 判定结束（成交/放弃），原地停留展示结果文本，停留结束后转 LEAVING */
        LINGER,
        /** 沿逆向路径离开中 */
        LEAVING,
        /** 换架中：在两货架间直线移动（带碰撞跳跃/绕行/超时传送兜底） */
        SWITCHING
    }

    // 不可变字段
    private final UUID uuid;
    private final int entityId;
    private final String name;
    private final SkinData skin;
    private final Equipment equipment;
    private final String shopId;
    /** NPC 当前所在货架 ID（可变：换架时由 switchShelf 更新）。 */
    private String currentShelfId;
    /** NPC 性格（spawn 时按权重随机分配，生命周期内不变，不持久化）。 */
    private final PersonalityProfile personality;
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

    // 徘徊判定参数（DELIBERATING 状态，由 NpcManager.startDeliberation 初始化）
    private int deliberationTotalRolls;
    private int deliberationRollsDone;
    private int deliberationTicksUntilNextRoll;
    private int deliberationIntervalTicks;
    private double deliberationProbability;

    // 判定后停留参数（LINGER 状态，由 startLingering 初始化）
    private int lingerTicksRemaining;

    // 换架参数（SWITCHING 状态，由 switchShelf() 初始化）
    private Location switchTargetLoc;
    private long switchDeadline;

    public SimNpc(UUID uuid, String name, SkinData skin, Equipment equipment, String shopId, String currentShelfId,
                  PersonalityProfile personality, Location spawnLocation,
                  double speed, double reachDistance,
                  int stuckThresholdSeconds, double stuckThresholdDistance) {
        this.uuid = uuid;
        this.entityId = ENTITY_ID_COUNTER.incrementAndGet();
        this.name = name;
        this.skin = skin;
        this.equipment = equipment;
        this.shopId = shopId;
        this.currentShelfId = currentShelfId;
        this.personality = personality;
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
     * 进入徘徊判定状态（DELIBERATING）。
     *
     * <p>由 NpcManager.handleReached 在 NPC 到达货架且 P>0 时调用。初始化判定
     * 次数/间隔/缓存概率，切换到 {@link State#DELIBERATING}。首次 roll 即时
     * 触发（{@code ticksUntilNextRoll=0}，下一 tick 即返回 {@link TickResult#ROLL_DUE}）。</p>
     *
     * <p>徘徊期间 NPC 原地不动，{@link #tick()} 在到点时返回 ROLL_DUE，由
     * NpcManager 执行 roll 并决定后续（命中→startLingering / 耗尽→startLingering /
     * 仍有余量→等待下次）。NpcManager 无需调用任何"重置计时器"方法——tick() 在
     * 返回 ROLL_DUE 时已将 ticksUntilNextRoll 重置为 intervalTicks。</p>
     *
     * @param totalRolls   总判定次数（由 impatience 映射，&gt;=1）
     * @param intervalTicks 后续判定间隔（ticks，首次即时）
     * @param probability  缓存购买概率 [0,1]（PurchaseFormula.calculate 一次的结果）
     */
    public void startDeliberation(int totalRolls, int intervalTicks, double probability) {
        this.deliberationTotalRolls = totalRolls;
        this.deliberationRollsDone = 0;
        this.deliberationIntervalTicks = intervalTicks;
        this.deliberationTicksUntilNextRoll = 0;
        this.deliberationProbability = probability;
        this.state = State.DELIBERATING;
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
        if (state == State.DELIBERATING) {
            return tickDeliberating();
        }
        if (state == State.LINGER) {
            return tickLinger();
        }
        if (state == State.SWITCHING) {
            return tickSwitching();
        }
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
     * DELIBERATING 状态的 tick 逻辑：倒计时到点返回 ROLL_DUE，否则 IDLE。
     *
     * <p>到点时 {@code rollsDone++} 并将 {@code ticksUntilNextRoll} 重置为
     * {@code intervalTicks}，返回 ROLL_DUE 交由 NpcManager 执行 roll。NpcManager
     * 据命中/耗尽决定 startLingering（state 变更后不再进入本方法）或等待下次。</p>
     *
     * @return ROLL_DUE（到点）或 IDLE（未到点）
     */
    private TickResult tickDeliberating() {
        if (deliberationTicksUntilNextRoll > 0) {
            deliberationTicksUntilNextRoll--;
            return TickResult.IDLE;
        }
        // 到点：推进计数 + 重置计时器 + 请求 roll
        deliberationRollsDone++;
        deliberationTicksUntilNextRoll = deliberationIntervalTicks;
        return TickResult.ROLL_DUE;
    }

    /**
     * SWITCHING 状态的 tick 逻辑：朝 {@link #switchTargetLoc} 直线移动，
     * 带碰撞跳跃 / 左右绕行 / 超时传送兜底。
     *
     * <p>不同于 MOVING 的路径点跟随，SWITCHING 是两点直线移动：
     * <ul>
     *   <li>超时（{@link #switchDeadline}）→ 直接传送至目标，返回 MOVING
     *       （让 NpcManager 发包同步客户端位置，下一 tick 因 dist&lt;reachDistance
     *       自然走到达分支返回 REACHED）</li>
     *   <li>到达（3D 距离 &lt; {@link #reachDistance}）→ 返回 REACHED</li>
     *   <li>前方 1 格高 + 上方可通行 → 跳上（my 提升至 1.0）</li>
     *   <li>前方 2+ 格高 → 尝试右偏 / 左偏绕行；左右都不通则停在原位等超时</li>
     * </ul>
     * </p>
     *
     * <p>移动后同样应用 Y 坐标处理（重力下坠 + 台阶攀爬 + 方块顶吸附），
     * 与 {@link #tick()} 一致，避免 NPC 在两货架间跨越深坑/高度差时悬空。</p>
     *
     * <p>到达后 state 仍为 SWITCHING（不切换），由 NpcManager 调用
     * {@link #resumeDeliberation()} 切回 DELIBERATING。</p>
     *
     * @return REACHED（到达）/ MOVING（移动一步或超时传送）/ IDLE（撞墙等待超时）
     */
    private TickResult tickSwitching() {
        // 超时检测：超时直接传送至目标，返回 MOVING 让客户端收包同步位置
        // （下一 tick 因 dist<reachDistance 自然走到达分支返回 REACHED）
        if (System.currentTimeMillis() > switchDeadline) {
            location.setX(switchTargetLoc.getX());
            location.setY(switchTargetLoc.getY());
            location.setZ(switchTargetLoc.getZ());
            return TickResult.MOVING;
        }

        // 朝目标的方向向量
        double dx = switchTargetLoc.getX() - location.getX();
        double dy = switchTargetLoc.getY() - location.getY();
        double dz = switchTargetLoc.getZ() - location.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 到达判定
        if (dist < reachDistance) {
            return TickResult.REACHED;
        }

        // 归一化方向 × speed
        double mx = (dx / dist) * speed;
        double my = (dy / dist) * speed;
        double mz = (dz / dist) * speed;

        // 碰撞检测：检查前方方块
        World world = location.getWorld();
        if (world != null) {
            Block nextBlock = world.getBlockAt(location.clone().add(mx, 0, mz));
            Block feetBlock = world.getBlockAt(location.clone().add(mx, 1, mz));
            if (nextBlock.getType().isSolid() && !feetBlock.getType().isSolid()) {
                // 前方 1 格高 + 上方可通行 → 跳上
                my = Math.max(my, 1.0);
            } else if (nextBlock.getType().isSolid() && feetBlock.getType().isSolid()) {
                // 前方 2+ 格高 → 尝试右偏（旋转 90°：-mz, mx）
                double rmx = -mz;
                double rmz = mx;
                if (!world.getBlockAt(location.clone().add(rmx, 0, rmz)).getType().isSolid()) {
                    mx = rmx;
                    mz = rmz;
                } else {
                    // 右偏不通，尝试左偏（旋转 -90°：mz, -mx）
                    double lmx = mz;
                    double lmz = -mx;
                    if (!world.getBlockAt(location.clone().add(lmx, 0, lmz)).getType().isSolid()) {
                        mx = lmx;
                        mz = lmz;
                    } else {
                        // 左右都不通 → 停在原位等超时兜底
                        return TickResult.IDLE;
                    }
                }
            }
        }

        // 更新 yaw 朝向移动方向（pitch=0 平视）
        float yaw = (float) Math.toDegrees(Math.atan2(-mx, mz));

        location.add(mx, my, mz);
        location.setYaw(yaw);

        // Y 坐标处理：重力下坠 + 台阶攀爬 + 方块顶吸附（与 tick() 一致）
        // 避免两货架间跨越深坑/高度差时 NPC 悬空飘浮
        if (world != null) {
            int bx = (int) Math.floor(location.getX());
            int by = (int) Math.floor(location.getY());
            int bz = (int) Math.floor(location.getZ());
            Block feet = world.getBlockAt(bx, by, bz);
            Block below = world.getBlockAt(bx, by - 1, bz);

            if (feet.getType().isSolid()) {
                // 脚位固体（穿模/上台阶）→ 上升到脚位方块顶部
                location.setY(by + 1.0);
            } else if (below.getType().isAir()) {
                // 脚下空气（悬空/下台阶）→ 重力下坠
                location.setY(location.getY() - GRAVITY_FALL);
            } else {
                // 脚下固体 → 吸附到方块顶（仅在偏差 > 阈值时才吸附，避免抖动）
                double top = (by - 1) + 1.0;
                if (Math.abs(location.getY() - top) > SNAP_THRESHOLD) {
                    location.setY(top);
                }
            }
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

    /**
     * 进入判定后停留状态（LINGER）：原地站立展示 BUY/GIVE_UP 文本，
     * 停留 {@code ticks} 后自动切换到 {@link State#LEAVING}。
     *
     * <p>由 NpcManager 在判定结束（成交或放弃）后调用，替代直接 {@link #startLeaving()}，
     * 让 NPC "看完商品再走开"，避免判定一结束就立刻转身离开的突兀感。
     * 停留期间 NPC 不移动，思考文本由 {@link ThoughtDisplayManager} 的 flash 机制展示。</p>
     *
     * <p>停留计时器倒数到 0 时，本方法内部调用 {@link #startLeaving()} 切换状态，
     * 下一 tick 起开始沿逆向路径离开。</p>
     *
     * @param ticks 停留时长（ticks，&gt;=0；0 表示立即离开，等价于直接调 startLeaving）
     */
    public void startLingering(int ticks) {
        if (ticks <= 0) {
            startLeaving();
            return;
        }
        this.lingerTicksRemaining = ticks;
        this.state = State.LINGER;
    }

    /**
     * LINGER 状态的 tick 逻辑：倒数计时器，到 0 时调用 {@link #startLeaving()} 切换到 LEAVING。
     *
     * <p>停留期间返回 {@link TickResult#IDLE}（不移动不发包）；
     * 切换到 LEAVING 当 tick 也返回 IDLE，下一 tick 起开始移动。</p>
     *
     * @return IDLE（停留中或刚切换到 LEAVING）
     */
    private TickResult tickLinger() {
        if (lingerTicksRemaining > 0) {
            lingerTicksRemaining--;
            return TickResult.IDLE;
        }
        startLeaving();
        return TickResult.IDLE;
    }

    /**
     * 切换到新货架：更新 currentShelfId + 目标位置 + 超时时间 + 状态切换为 SWITCHING。
     *
     * <p>由 NpcManager.switchToRandomShelf 在判定未命中且 roll 命中换架概率时调用。
     * 调用后 NPC 进入 SWITCHING 状态，tick 沿直线移动至 {@code targetLoc}，
     * 到达或超时后由 NpcManager 调 {@link #resumeDeliberation()} 切回 DELIBERATING。</p>
     *
     * <p>注意：调用方应先 {@link ThoughtDisplayManager#despawn} 移除旧货架的思考展示，
     * 换架期间不展示思考文本（NPC 在移动中）。</p>
     *
     * @param newShelfId    新货架 ID
     * @param targetLoc     新货架位置（方块中心坐标）
     * @param deadlineMillis 超时时间戳（{@link System#currentTimeMillis()}）
     */
    public void switchShelf(String newShelfId, Location targetLoc, long deadlineMillis) {
        this.currentShelfId = newShelfId;
        this.switchTargetLoc = targetLoc;
        this.switchDeadline = deadlineMillis;
        this.state = State.SWITCHING;
    }

    /**
     * 换架到达后恢复徘徊：不重置判定次数，仅切换状态为 DELIBERATING。
     *
     * <p>由 NpcManager.handleSwitchArrival 在 NPC 换架到达后调用。P 已由外部
     * {@link #updateDeliberationProbability} 更新；判定计数（rollsDone/totalRolls）
     * 保留换架前的累计值，NPC 继续在新货架上完成剩余判定。</p>
     *
     * <p>计时器 {@code deliberationTicksUntilNextRoll} 设为 0，下一 tick 即
     * 触发 ROLL_DUE，让 NpcManager 立即执行一次新货架的 roll。</p>
     */
    public void resumeDeliberation() {
        this.deliberationTicksUntilNextRoll = 0;
        this.state = State.DELIBERATING;
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

    public String currentShelfId() {
        return currentShelfId;
    }

    /**
     * 获取 NPC 性格（spawn 时确定，生命周期内不变）。
     *
     * @return 性格 profile
     */
    public PersonalityProfile personality() {
        return personality;
    }

    public Location location() {
        return location;
    }

    public State state() {
        return state;
    }

    /** 徘徊判定已完成次数（供 NpcManager.handleRoll 判定耗尽 + 子系统 4 展示进度）。 */
    public int deliberationRollsDone() {
        return deliberationRollsDone;
    }

    /** 徘徊判定总次数（供 NpcManager.handleRoll 判定耗尽 + 子系统 4 展示进度）。 */
    public int deliberationTotalRolls() {
        return deliberationTotalRolls;
    }

    /** 缓存的购买概率（handleReached 时由 PurchaseFormula.calculate 计算一次）。 */
    public double deliberationProbability() {
        return deliberationProbability;
    }

    /**
     * 更新缓存的购买概率（换架到达后由 NpcManager 调用，重算 P 后写回）。
     *
     * @param p 新概率 [0,1]
     */
    public void updateDeliberationProbability(double p) {
        this.deliberationProbability = p;
    }

    public long spawnTime() {
        return spawnTime;
    }
}
