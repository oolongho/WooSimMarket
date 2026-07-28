package com.oolongho.woosimmarket.listener;

import com.oolongho.woosimmarket.npc.NpcPacketSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家事件监听器 —— 维护 NPC 发包追踪状态。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link PlayerQuitEvent}：清理追踪记录，防止内存泄漏</li>
 *   <li>{@link PlayerChangedWorldEvent}：清理追踪记录（客户端切换世界时自动移除所有实体，
 *       追踪记录与客户端状态不同步会导致 auto-spawn 失效）</li>
 * </ul></p>
 *
 * <p>玩家加入、同世界传送、走回 NPC 视距内等场景由
 * {@link NpcPacketSender#move} 的 auto-spawn 机制自动处理，无需此处干预。</p>
 *
 * @author oolongho
 */
public class PlayerListener implements Listener {

    private final NpcPacketSender npcPacketSender;

    public PlayerListener(NpcPacketSender npcPacketSender) {
        this.npcPacketSender = npcPacketSender;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        npcPacketSender.removePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        npcPacketSender.removePlayer(event.getPlayer().getUniqueId());
    }
}
