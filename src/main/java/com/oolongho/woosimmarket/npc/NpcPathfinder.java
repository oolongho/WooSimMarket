package com.oolongho.woosimmarket.npc;

import com.oolongho.woosimmarket.WooSimMarket;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A* 网格寻路算法 —— 异步计算 NPC 从起点到目标点的可行走路径。
 *
 * <p>线程模型：主线程收集 {@link ChunkSnapshot}（线程安全快照），异步线程在快照上
 * 运行 A*，主线程回调。整个流程不阻塞主线程。</p>
 *
 * <p>节点表示：方块坐标 (x, y, z)。邻居为 4 个水平方向（N/S/E/W），
 * 启发函数为 3D 欧几里得距离到目标。</p>
 *
 * <p>可行走判定：脚下方块（y-1）固体，脚位（y）与头位（y+1）均为空气。
 * 自动攀爬 1 格高台阶（代价 1.5），下台阶代价 1.0。</p>
 *
 * <p>路径构建：从目标回溯到起点，路径不含起点、含终点；每个路径点为方块中心
 * {@code (x + 0.5, y, z + 0.5)}。</p>
 *
 * @author oolongho
 */
public final class NpcPathfinder {

    /** 寻路危险方块集合（isWalkable 拒绝踏入）。 */
    private static final Set<Material> HAZARD_BLOCKS = Set.of(
            Material.FIRE, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POINTED_DRIPSTONE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private NpcPathfinder() {
    }

    /**
     * 异步计算路径。
     *
     * @param plugin        插件实例（用于调度）
     * @param world         世界
     * @param start         起点位置
     * @param target        目标位置
     * @param maxDistance   最大搜索距离（方块）
     * @param maxIterations 最大迭代节点数
     * @param callback      回调（主线程执行），传入路径 {@code List<Location>} 或 {@code null}（无路径）
     */
    public static void findPath(WooSimMarket plugin, World world,
                                Location start, Location target,
                                int maxDistance, int maxIterations,
                                Consumer<List<Location>> callback) {
        boolean debug = plugin.getConfigLoader().isDebug();
        boolean avoidHazards = plugin.getConfigLoader().isPathfindingAvoidHazards();

        if (debug) {
            plugin.getLogger().info(() -> String.format(
                    "[Pathfinder] 寻路请求: start=(%d,%d,%d) target=(%d,%d,%d) maxDist=%d maxIter=%d",
                    start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                    maxDistance, maxIterations));
        }

        // 主线程阶段：收集 ChunkSnapshot
        Map<Long, ChunkSnapshot> snapshots = collectSnapshots(world, start, target, maxDistance);

        if (debug) {
            plugin.getLogger().info(() -> "[Pathfinder] 已收集 " + snapshots.size() + " 个区块快照");
        }

        // 异步阶段：A* 算法在 ChunkSnapshot 上运行
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Location> path;
            try {
                path = computePath(snapshots, world, start, target, maxIterations, avoidHazards);
            } catch (Exception e) {
                plugin.getLogger().warning("A* 寻路异常: " + e.getMessage());
                path = null;
            }
            // 回调阶段：切换回主线程
            final List<Location> result = path;
            if (debug) {
                if (result == null) {
                    plugin.getLogger().info(() -> "[Pathfinder] 寻路失败: 未找到路径（迭代上限或开放集耗尽）");
                } else if (result.isEmpty()) {
                    plugin.getLogger().info(() -> "[Pathfinder] 寻路完成: 路径为空（起点已相邻于目标）");
                } else {
                    plugin.getLogger().info(() -> "[Pathfinder] 寻路成功: " + result.size() + " 个路径点");
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /**
     * 主线程阶段：收集起点到目标之间区域的 ChunkSnapshot。
     *
     * <p>搜索边界：以 start 和 target 为对角，向外扩大 maxDistance 的矩形区域。
     * 仅收集已加载的区块（未加载区块视为空气）。</p>
     *
     * @param world       世界
     * @param start       起点位置
     * @param target      目标位置
     * @param maxDistance 最大搜索距离（方块）
     * @return 区块快照映射，key 为 {@code (long)cx << 32 | (cz & 0xFFFFFFFFL)}
     */
    private static Map<Long, ChunkSnapshot> collectSnapshots(World world, Location start, Location target, int maxDistance) {
        int minX = Math.min(start.getBlockX(), target.getBlockX()) - maxDistance;
        int maxX = Math.max(start.getBlockX(), target.getBlockX()) + maxDistance;
        int minZ = Math.min(start.getBlockZ(), target.getBlockZ()) - maxDistance;
        int maxZ = Math.max(start.getBlockZ(), target.getBlockZ()) + maxDistance;

        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                ChunkSnapshot snap = world.getChunkAt(cx, cz).getChunkSnapshot();
                long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                snapshots.put(key, snap);
            }
        }
        return snapshots;
    }

    /**
     * 异步阶段：在 ChunkSnapshot 上运行 A* 算法。
     *
     * @param snapshots     区块快照映射
     * @param world         世界（用于构建 Location）
     * @param start         起点位置
     * @param target        目标位置
     * @param maxIterations 最大迭代节点数
     * @param avoidHazards  是否规避危险方块
     * @return 路径列表（不含起点，含终点，已平滑）；无路径返回 {@code null}
     */
    private static List<Location> computePath(Map<Long, ChunkSnapshot> snapshots, World world,
                                              Location start, Location target, int maxIterations,
                                              boolean avoidHazards) {
        int startX = start.getBlockX();
        int startY = start.getBlockY();
        int startZ = start.getBlockZ();
        int targetX = target.getBlockX();
        int targetY = target.getBlockY();
        int targetZ = target.getBlockZ();

        Node startNode = new Node(startX, startY, startZ);
        startNode.g = 0;
        startNode.h = heuristic(startX, startY, startZ, targetX, targetY, targetZ);

        // 开放集：按 f = g + h 升序
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        open.add(startNode);

        // 关闭集：已展开节点
        Set<Long> closed = new HashSet<>();

        // 已知最优 g 值映射（惰性删除策略：仅当新 g 优于已知值时才入队）
        Map<Long, Double> bestG = new HashMap<>();
        bestG.put(encodeNode(startX, startY, startZ), 0.0);

        int iterations = 0;
        while (!open.isEmpty()) {
            if (++iterations > maxIterations) {
                return null;
            }

            Node current = open.poll();
            long currentKey = encodeNode(current.x, current.y, current.z);
            // 惰性删除：跳过已处理或过期条目
            if (!closed.add(currentKey)) {
                continue;
            }

            // 到达目标附近 —— 货架本身为固体方块不可行走，NPC 停在相邻可行走方块即可
            // （XZ 1 格、Y 1 格容差，覆盖平走/上下台阶情形；current 已保证可行走）
            if (Math.abs(current.x - targetX) <= 1
                    && Math.abs(current.y - targetY) <= 1
                    && Math.abs(current.z - targetZ) <= 1) {
                return smoothPath(buildPath(current, world), snapshots, start);
            }

            // 遍历 4 个水平方向邻居
            for (int[] dir : DIRECTIONS) {
                expandNeighbor(snapshots, current, dir[0], dir[1],
                        targetX, targetY, targetZ, open, closed, bestG, avoidHazards);
            }
        }
        return null;
    }

    /** 4 个水平方向：E, W, S, N。 */
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    /**
     * 扩展一个方向的邻居：并列尝试平走 / 上台阶 / 下台阶，由 g 值择优。
     *
     * <p>三种情况并非互斥（如 2 格高空气柱时上台阶与下台阶均可行），
     * 故全部尝试入队，重复节点由 closed / bestG 过滤。</p>
     *
     * @param snapshots    区块快照
     * @param current      当前节点
     * @param dx           X 方向偏移
     * @param dz           Z 方向偏移
     * @param targetX      目标 X
     * @param targetY      目标 Y
     * @param targetZ      目标 Z
     * @param open         开放集
     * @param closed       关闭集
     * @param bestG        已知最优 g 值映射
     * @param avoidHazards 是否规避危险方块
     */
    private static void expandNeighbor(Map<Long, ChunkSnapshot> snapshots, Node current,
                                       int dx, int dz,
                                       int targetX, int targetY, int targetZ,
                                       PriorityQueue<Node> open, Set<Long> closed, Map<Long, Double> bestG,
                                       boolean avoidHazards) {
        int nx = current.x + dx;
        int nz = current.z + dz;

        // 1. 平走：(nx, y, nz) 可行走，代价 1.0
        if (isWalkable(snapshots, nx, current.y, nz, avoidHazards)) {
            tryAddNode(nx, current.y, nz, current, 1.0,
                    targetX, targetY, targetZ, open, closed, bestG);
        }
        // 2. 上台阶：前方+1格可行走，代价 1.5
        if (isWalkable(snapshots, nx, current.y + 1, nz, avoidHazards)) {
            tryAddNode(nx, current.y + 1, nz, current, 1.5,
                    targetX, targetY, targetZ, open, closed, bestG);
        }
        // 3. 下台阶：前方-1格可行走，代价 1.0
        if (isWalkable(snapshots, nx, current.y - 1, nz, avoidHazards)) {
            tryAddNode(nx, current.y - 1, nz, current, 1.0,
                    targetX, targetY, targetZ, open, closed, bestG);
        }
    }

    /**
     * 尝试添加或更新邻居节点（惰性删除策略）。
     *
     * <p>若新路径比已知最优 g 更优，则创建新节点入队；旧条目在出队时被
     * {@code closed} 集合过滤。</p>
     */
    private static void tryAddNode(int nx, int ny, int nz, Node parent, double cost,
                                   int targetX, int targetY, int targetZ,
                                   PriorityQueue<Node> open, Set<Long> closed, Map<Long, Double> bestG) {
        long key = encodeNode(nx, ny, nz);
        if (closed.contains(key)) {
            return;
        }
        double newG = parent.g + cost;
        Double known = bestG.get(key);
        if (known != null && known <= newG) {
            return; // 已存在更优或相等路径
        }
        bestG.put(key, newG);

        Node node = new Node(nx, ny, nz);
        node.g = newG;
        node.h = heuristic(nx, ny, nz, targetX, targetY, targetZ);
        node.parent = parent;
        open.add(node);
    }

    /**
     * 启发函数：3D 欧几里得距离到目标。
     */
    private static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 从目标节点回溯构建路径点列表。
     *
     * <p>路径不含起点，含终点。每个路径点为方块中心：
     * {@code new Location(world, x + 0.5, y, z + 0.5)}。</p>
     *
     * @param target 目标节点
     * @param world  世界
     * @return 路径列表（起点在前，目标在后）
     */
    private static List<Location> buildPath(Node target, World world) {
        List<Location> path = new ArrayList<>();
        for (Node node = target; node != null; node = node.parent) {
            path.add(new Location(world, node.x + 0.5, node.y, node.z + 0.5));
        }
        // 反转使起点在前
        Collections.reverse(path);
        // 移除起点（路径不含起点）
        if (!path.isEmpty()) {
            path.remove(0);
        }
        return path;
    }

    /**
     * 路径平滑（string pulling）：从起点开始，对每个路径点找最远可直视的下游点，
     * 跳过中间点，得到最短折线路径。O(n²) 最坏复杂度，n 为路径点数。
     *
     * <p>算法：以 anchor（起点或上一个保留点）为基准，从路径末端反向扫描第一个
     * 有 LOS 的点 farthest，加入结果并设为新 anchor；从 farthest+1 继续迭代。
     * 路径点本身保持不动，仅修改路径点列表（不修改起点）。</p>
     *
     * @param path      A* 返回的路径点列表（不含起点，含终点，方块中心坐标）
     * @param snapshots 区块快照
     * @param start     NPC 当前位置（方块坐标）
     * @return 平滑后的路径点列表
     */
    private static List<Location> smoothPath(List<Location> path, Map<Long, ChunkSnapshot> snapshots, Location start) {
        if (path.size() <= 1) {
            return path;
        }
        List<Location> result = new ArrayList<>();
        Location anchor = start;
        int i = 0;
        int n = path.size();
        while (i < n) {
            // 从最远端反向找第一个有 LOS 的点
            int farthest = i;
            for (int j = n - 1; j > i; j--) {
                if (hasLineOfSight(snapshots, anchor, path.get(j))) {
                    farthest = j;
                    break;
                }
            }
            result.add(path.get(farthest));
            anchor = path.get(farthest);
            i = farthest + 1;
        }
        return result;
    }

    /**
     * 视线检查：用 Bresenham 3D 直线算法枚举 from 到 to 之间所有中间方块（不含端点），
     * 任一中间方块的脚位或头位非空气则视为阻挡。仅读取 ChunkSnapshot（异步线程安全）。
     *
     * @param snapshots 区块快照
     * @param from      起点（仅取方块坐标）
     * @param to        目标点（仅取方块坐标）
     * @return 无阻挡返回 true
     */
    private static boolean hasLineOfSight(Map<Long, ChunkSnapshot> snapshots, Location from, Location to) {
        int x1 = from.getBlockX();
        int y1 = from.getBlockY();
        int z1 = from.getBlockZ();
        int x2 = to.getBlockX();
        int y2 = to.getBlockY();
        int z2 = to.getBlockZ();

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);
        int xs = Integer.compare(x2, x1);
        int ys = Integer.compare(y2, y1);
        int zs = Integer.compare(z2, z1);

        // 3D Bresenham：选 delta 最大的轴为驱动轴，另两轴按误差累积步进
        if (dx >= dy && dx >= dz) {
            // X 驱动
            int y = y1, z = z1;
            int errY = 2 * dy - dx;
            int errZ = 2 * dz - dx;
            for (int x = x1 + xs; x != x2; x += xs) {
                if (errY > 0) { y += ys; errY -= 2 * dx; }
                if (errZ > 0) { z += zs; errZ -= 2 * dx; }
                errY += 2 * dy;
                errZ += 2 * dz;
                if (!isClearForPassage(snapshots, x, y, z)) {
                    return false;
                }
            }
        } else if (dy >= dx && dy >= dz) {
            // Y 驱动
            int x = x1, z = z1;
            int errX = 2 * dx - dy;
            int errZ = 2 * dz - dy;
            for (int y = y1 + ys; y != y2; y += ys) {
                if (errX > 0) { x += xs; errX -= 2 * dy; }
                if (errZ > 0) { z += zs; errZ -= 2 * dy; }
                errX += 2 * dx;
                errZ += 2 * dz;
                if (!isClearForPassage(snapshots, x, y, z)) {
                    return false;
                }
            }
        } else {
            // Z 驱动
            int x = x1, y = y1;
            int errX = 2 * dx - dz;
            int errY = 2 * dy - dz;
            for (int z = z1 + zs; z != z2; z += zs) {
                if (errX > 0) { x += xs; errX -= 2 * dz; }
                if (errY > 0) { y += ys; errY -= 2 * dz; }
                errX += 2 * dx;
                errY += 2 * dy;
                if (!isClearForPassage(snapshots, x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 检查方块通道是否畅通：脚位与头位均为空气。
     */
    private static boolean isClearForPassage(Map<Long, ChunkSnapshot> snapshots, int x, int y, int z) {
        return getBlockType(snapshots, x, y, z).isAir()
                && getBlockType(snapshots, x, y + 1, z).isAir();
    }

    /**
     * 从 ChunkSnapshot 读取方块类型。未加载区块视为空气。
     *
     * @param snapshots 区块快照映射
     * @param x         方块 X 坐标
     * @param y         方块 Y 坐标
     * @param z         方块 Z 坐标
     * @return 方块材质；快照不存在或越界返回 {@link Material#AIR}
     */
    private static Material getBlockType(Map<Long, ChunkSnapshot> snapshots, int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
        ChunkSnapshot snap = snapshots.get(key);
        if (snap == null) {
            return Material.AIR; // 未加载区块视为空气
        }
        try {
            return snap.getBlockType(x & 15, y, z & 15);
        } catch (IndexOutOfBoundsException e) {
            // Y 越界（世界高度范围外）视为空气
            return Material.AIR;
        }
    }

    /**
     * 可行走判定：脚下方块固体，脚位与头位均为空气；开启规避时拒绝危险方块。
     *
     * @param snapshots    区块快照映射
     * @param x            方块 X 坐标
     * @param y            脚位 Y 坐标
     * @param z            方块 Z 坐标
     * @param avoidHazards 是否规避危险方块
     * @return 可行走返回 true
     */
    private static boolean isWalkable(Map<Long, ChunkSnapshot> snapshots, int x, int y, int z,
                                      boolean avoidHazards) {
        Material below = getBlockType(snapshots, x, y - 1, z);
        Material feet = getBlockType(snapshots, x, y, z);
        Material head = getBlockType(snapshots, x, y + 1, z);
        if (!(below.isSolid() && feet.isAir() && head.isAir())) {
            return false;
        }
        if (avoidHazards && (HAZARD_BLOCKS.contains(below) || HAZARD_BLOCKS.contains(feet))) {
            return false;
        }
        return true;
    }

    /**
     * 节点编码：x 占 22 位（带符号），y 占 22 位（掩码），z 占 20 位（掩码）。
     *
     * <p>编码格式：{@code (long)x << 42 | (long)(y & 0x3FFFFF) << 20 | (z & 0xFFFFF)}<br>
     * 用于关闭集与最优 g 映射的 key。</p>
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 节点编码
     */
    private static long encodeNode(int x, int y, int z) {
        return (long) x << 42 | (long) (y & 0x3FFFFF) << 20 | (z & 0xFFFFF);
    }

    /**
     * A* 节点。
     *
     * <p>不可变坐标 (x, y, z) + 可变代价值 (g, h) + 父指针。
     * equals/hashCode 仅基于坐标。</p>
     */
    private static final class Node {
        final int x, y, z;
        double g; // 已走代价
        double h; // 启发值
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** f = g + h。 */
        double f() {
            return g + h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node other)) return false;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(encodeNode(x, y, z));
        }
    }
}
