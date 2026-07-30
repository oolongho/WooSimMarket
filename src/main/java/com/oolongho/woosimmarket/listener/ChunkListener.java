package com.oolongho.woosimmarket.listener;

import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * 区块加载监听器 —— 补齐货架展示实体。
 *
 * <p>当区块加载时，委托 {@link ShelfDisplayManager#onChunkLoad} 清理残留实体
 * 并为该区块内的合格货架生成展示。展示实体标记为非持久化，区块卸载时自动移除，
 * 故无需监听 ChunkUnloadEvent。</p>
 *
 * @author oolongho
 */
public class ChunkListener implements Listener {

    private final ShelfDisplayManager shelfDisplayManager;

    public ChunkListener(ShelfDisplayManager shelfDisplayManager) {
        this.shelfDisplayManager = shelfDisplayManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        // 新生成的区块不可能有已绑定的货架（货架由玩家在已加载区块放置），
        // 跳过避免无意义的全量货架遍历
        if (event.isNewChunk()) {
            return;
        }
        shelfDisplayManager.onChunkLoad(event.getChunk());
    }
}
