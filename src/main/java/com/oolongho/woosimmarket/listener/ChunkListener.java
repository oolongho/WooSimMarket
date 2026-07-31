package com.oolongho.woosimmarket.listener;

import com.oolongho.woosimmarket.visualize.ShelfDisplayManager;
import com.oolongho.woosimmarket.visualize.ShopDisplayManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * 区块加载监听器 —— 补齐货架与收银台展示实体。
 *
 * <p>当区块加载时，委托 {@link ShelfDisplayManager#onChunkLoad} 与
 * {@link ShopDisplayManager#onChunkLoad} 清理残留实体并为该区块内的合格货架/商店
 * 生成展示。展示实体标记为非持久化，区块卸载时自动移除，故无需监听 ChunkUnloadEvent。</p>
 *
 * @author oolongho
 */
public class ChunkListener implements Listener {

    private final ShelfDisplayManager shelfDisplayManager;
    private final ShopDisplayManager shopDisplayManager;

    public ChunkListener(ShelfDisplayManager shelfDisplayManager, ShopDisplayManager shopDisplayManager) {
        this.shelfDisplayManager = shelfDisplayManager;
        this.shopDisplayManager = shopDisplayManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        // 新生成的区块不可能有已绑定的货架/商店（均由玩家在已加载区块放置），
        // 跳过避免无意义的全量遍历
        if (event.isNewChunk()) {
            return;
        }
        shelfDisplayManager.onChunkLoad(event.getChunk());
        shopDisplayManager.onChunkLoad(event.getChunk());
    }
}
