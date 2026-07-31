package com.oolongho.woosimmarket.visualize;

import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.config.ConfigLoader;
import com.oolongho.woosimmarket.config.Messages;
import com.oolongho.woosimmarket.npc.SimNpc;
import com.oolongho.woosimmarket.util.TaskUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 头顶思考展示管理器（子系统 4）。
 *
 * <p>在 NPC 进入 DELIBERATING 状态时于其头顶生成真实 {@link TextDisplay} 实体，
 * 按判定子阶段切换性格化文本（ENTER/HESITATE/BUY/GIVE_UP）。命中或耗尽后文本
 * 原地短暂停留（flash）再回收。所有展示实体携带 scoreboard tag
 * {@value #SCOREBOARD_TAG} 与 PDC 键 {@code thoughtNpcUuid}，便于溯源。</p>
 *
 * <p>线程模型：所有方法在主线程调用（实体生成/移除为主线程操作）。
 * 内存索引使用 {@link ConcurrentHashMap} 保证可见性。</p>
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #spawn} —— handleReached 进入 DELIBERATING 时调用</li>
 *   <li>{@link #update} —— handleRoll miss+余量时切换 HESITATE 文本</li>
 *   <li>{@link #flash} —— handleRoll hit/耗尽时切换结果文本 + 定时 despawn</li>
 *   <li>{@link #despawn} —— handleRoll 货架失效 / despawnNpc 时立即回收</li>
 *   <li>{@link #clearAll} —— stop() 兜底全量清理</li>
 * </ul></p>
 *
 * <p>镜像 {@link ShelfDisplayManager} 模式：scoreboard tag + setPersistent(false) +
 * PDC + 异常安全回收。区别在于 TextDisplay 短生命周期（仅 DELIBERATING 期间），
 * 无 init/onChunkLoad（不持久化，无需崩溃残留清理）。</p>
 *
 * @author oolongho
 */
public class ThoughtDisplayManager {

    /** Scoreboard tag，标记所有由本管理器生成的思考展示实体。 */
    private static final String SCOREBOARD_TAG = "woosimmarket_thought_display";

    private final WooSimMarket plugin;
    private final ConfigLoader configLoader;
    private final Messages messages;
    private final NamespacedKey thoughtNpcUuidKey;
    private final MiniMessage miniMessage;

    /** npcUuid → 展示句柄（TextDisplay 引用 + 可变 flashTask）。 */
    private final Map<UUID, ThoughtHandle> handles = new ConcurrentHashMap<>();

    /** 思考展示阶段。 */
    public enum Phase {
        /** 进入 DELIBERATING（spawn 时）。 */
        ENTER,
        /** roll 未命中且仍有余量。 */
        HESITATE,
        /** roll 命中（flash）。 */
        BUY,
        /** roll 耗尽（flash）。 */
        GIVE_UP;

        /**
         * 对应的 lang 键后缀（GIVE_UP → "give-up"，其余 → 小写名）。
         *
         * @return lang 键后缀
         */
        public String key() {
            return this == GIVE_UP ? "give-up" : name().toLowerCase();
        }
    }

    /** 展示句柄：TextDisplay 引用 + 可变 flashTask（非 record，flashTask 需可变）。 */
    private static final class ThoughtHandle {
        final TextDisplay entity;
        BukkitTask flashTask;

        ThoughtHandle(TextDisplay entity) {
            this.entity = entity;
        }
    }

    public ThoughtDisplayManager(WooSimMarket plugin, ConfigLoader configLoader, Messages messages) {
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.messages = messages;
        this.thoughtNpcUuidKey = new NamespacedKey(plugin, "thoughtNpcUuid");
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * 在 NPC 头顶生成 TextDisplay 并设置初始阶段文本。
     *
     * <p>仅在 {@code handleReached} P>0 分支调用。位置 = NPC 脚位 + (0, yOffset, 0)。
     * 若 NPC 已有 handle 则覆盖（不应发生，防御性）。</p>
     *
     * @param npc   NPC
     * @param phase 初始阶段（通常 ENTER）
     */
    public void spawn(SimNpc npc, Phase phase) {
        if (!configLoader.isThoughtDisplayEnabled()) {
            return;
        }
        Location loc = npc.location();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        // 防御性清理旧 handle（spec 要求覆盖语义，不应发生但防御）
        despawn(npc);

        Location displayLoc = loc.clone().add(0, configLoader.getThoughtDisplayYOffset(), 0);
        TextDisplay display = world.spawn(displayLoc, TextDisplay.class, entity -> {
            entity.setBillboard(configLoader.getThoughtDisplayBillboard());
            entity.setBackgroundColor(configLoader.getThoughtDisplayBackgroundColor());
            entity.setDefaultBackground(false);
            entity.setShadowed(configLoader.isThoughtDisplayShadow());
            entity.setSeeThrough(configLoader.isThoughtDisplaySeeThrough());
            entity.setPersistent(false);
            entity.addScoreboardTag(SCOREBOARD_TAG);
            entity.getPersistentDataContainer().set(
                    thoughtNpcUuidKey, PersistentDataType.STRING, npc.uuid().toString());
            entity.text(buildText(npc, phase));
        });
        handles.put(npc.uuid(), new ThoughtHandle(display));
    }

    /**
     * 切换已有 TextDisplay 的文本到指定阶段（不 flash，不调度 despawn）。
     *
     * <p>仅在 {@code handleRoll} miss+余量时调用（切 HESITATE）。handle 不存在
     * 或实体无效时静默返回（区块卸载降级）。</p>
     *
     * @param npc   NPC
     * @param phase 目标阶段
     */
    public void update(SimNpc npc, Phase phase) {
        if (!configLoader.isThoughtDisplayEnabled()) {
            return;
        }
        ThoughtHandle handle = handles.get(npc.uuid());
        if (handle == null) {
            return;
        }
        if (!handle.entity.isValid()) {
            handles.remove(npc.uuid());
            return;
        }
        handle.entity.text(buildText(npc, phase));
    }

    /**
     * 切换文本到结果阶段并调度定时 despawn。
     *
     * <p>仅在 {@code handleRoll} hit（BUY）/ 耗尽（GIVE_UP）时调用。流程：
     * 取 handle → 校验 isValid → 设 text → cancel 旧 flashTask → 调度新 flashTask。
     * flash 期间 TextDisplay 原地不动，NPC 同时 startLeaving 开始走开。</p>
     *
     * @param npc   NPC
     * @param phase 结果阶段（BUY 或 GIVE_UP）
     */
    public void flash(SimNpc npc, Phase phase) {
        if (!configLoader.isThoughtDisplayEnabled()) {
            return;
        }
        ThoughtHandle handle = handles.get(npc.uuid());
        if (handle == null) {
            return;
        }
        if (!handle.entity.isValid()) {
            handles.remove(npc.uuid());
            return;
        }
        handle.entity.text(buildText(npc, phase));
        if (handle.flashTask != null) {
            handle.flashTask.cancel();
        }
        handle.flashTask = TaskUtil.runLater(
                plugin, () -> despawn(npc), configLoader.getThoughtDisplayFlashDurationTicks());
    }

    /**
     * 立即回收 NPC 的 TextDisplay（无 flash）。
     *
     * <p>由 {@code handleRoll} 货架失效 / {@code despawnNpc} 调用。幂等：
     * handle 不存在则静默返回。</p>
     *
     * @param npc NPC
     */
    public void despawn(SimNpc npc) {
        if (!configLoader.isThoughtDisplayEnabled()) {
            return;
        }
        ThoughtHandle handle = handles.remove(npc.uuid());
        if (handle == null) {
            return;
        }
        if (handle.flashTask != null) {
            handle.flashTask.cancel();
        }
        if (handle.entity.isValid()) {
            handle.entity.remove();
        }
    }

    /**
     * 移除所有思考展示实体并清空内存索引（stop() 兜底调用）。
     */
    public void clearAll() {
        if (!configLoader.isThoughtDisplayEnabled()) {
            return;
        }
        for (ThoughtHandle handle : handles.values()) {
            if (handle.flashTask != null) {
                handle.flashTask.cancel();
            }
            if (handle.entity.isValid()) {
                handle.entity.remove();
            }
        }
        handles.clear();
    }

    /**
     * 构建思考文本 Component（spawn/update/flash 共用）。
     *
     * <p>取 {@link Messages#thoughtText} 性格化文本，若 isDebug 且阶段为
     * ENTER/HESITATE 则追加 {@link Messages#thoughtDebugSuffix}。
     * MiniMessage 解析失败时降级为字面文本。</p>
     *
     * @param npc   NPC
     * @param phase 阶段
     * @return Adventure Component
     */
    private Component buildText(SimNpc npc, Phase phase) {
        String text = messages.thoughtText(npc.personality().name(), phase.key());
        if (configLoader.isDebugGeneral() && (phase == Phase.ENTER || phase == Phase.HESITATE)) {
            text = text + messages.thoughtDebugSuffix(
                    npc.deliberationProbability(),
                    npc.deliberationRollsDone(),
                    npc.deliberationTotalRolls());
        }
        try {
            return miniMessage.deserialize(text);
        } catch (RuntimeException ex) {
            return Component.text(text);
        }
    }
}
