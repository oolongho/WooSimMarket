package com.oolongho.woosimmarket.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oolongho.woosimmarket.WooSimMarket;
import com.oolongho.woosimmarket.util.SkinFetcher;
import com.oolongho.woosimmarket.util.TaskUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * NPC 皮肤缓存 —— playerName → {@link SimNpc.SkinData} 映射 + 磁盘持久化。
 *
 * <p>线程模型：内存 Map 使用 {@link ConcurrentHashMap}，主线程读（{@link #getSkin}）
 * 无锁；异步线程写（{@link #preloadAsync} 通过 {@link TaskUtil#runAsync} 调度）
 * 通过 ConcurrentHashMap 保证可见性。</p>
 *
 * <p>持久化：缓存文件位于插件数据目录的 {@code data/} 子文件夹（默认 {@code data/skins.json}），使用 Gson
 * 手动序列化为 JSON。{@link #save()} 异步落盘，避免阻塞主线程。</p>
 *
 * <p>预加载策略：插件启动时 {@link #preloadAsync} 异步拉取所有配置的皮肤名，
 * 拉取完成后后续 NPC 生成直接命中缓存。启动初期缓存未就绪时 NPC 使用 null
 * 皮肤（Steve 兜底，由 {@link NpcPacketSender} 处理）。</p>
 *
 * @author oolongho
 */
public class NpcSkinCache {

    private final WooSimMarket plugin;
    private final Path cacheFile;
    private final int fetchTimeoutSeconds;

    /** 内存缓存：playerName → 皮肤数据。 */
    private final Map<String, SimNpc.SkinData> cache = new ConcurrentHashMap<>();

    public NpcSkinCache(WooSimMarket plugin, String cacheFileName, int fetchTimeoutSeconds) {
        this.plugin = plugin;
        this.cacheFile = plugin.getDataFolder().toPath().resolve(cacheFileName);
        this.fetchTimeoutSeconds = fetchTimeoutSeconds;
    }

    /**
     * 从磁盘加载缓存文件到内存。
     *
     * <p>文件不存在或格式异常时静默跳过（空缓存，后续预加载拉取）。</p>
     */
    public void load() {
        ensureParentDir();
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject skins = root.getAsJsonObject("skins");
            if (skins == null) {
                return;
            }
            for (var entry : skins.entrySet()) {
                JsonObject skin = entry.getValue().getAsJsonObject();
                String value = skin.get("value").getAsString();
                String signature = skin.has("signature") && !skin.get("signature").isJsonNull()
                        ? skin.get("signature").getAsString() : null;
                cache.put(entry.getKey(), new SimNpc.SkinData(value, signature));
            }
            plugin.getLogger().info(() -> "皮肤缓存加载完成: " + cache.size() + " 条");
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "皮肤缓存加载失败，将重新拉取: " + e.getMessage());
        }
    }

    /**
     * 确保缓存文件的父目录存在（归档至 data/ 子目录时自动创建）。
     */
    private void ensureParentDir() {
        File parent = cacheFile.toFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning(() -> "Cannot create data directory: " + parent);
        }
    }

    /**
     * 异步预加载：拉取不在缓存中的皮肤名，拉取后更新缓存并落盘。
     *
     * @param names 需要预加载的玩家名列表
     */
    public void preloadAsync(List<String> names) {
        TaskUtil.runAsync(plugin, () -> {
            int fetched = 0;
            int failed = 0;
            for (String name : names) {
                if (cache.containsKey(name)) {
                    continue;
                }
                SimNpc.SkinData skin = SkinFetcher.fetch(name, fetchTimeoutSeconds);
                if (skin != null) {
                    cache.put(name, skin);
                    fetched++;
                } else {
                    failed++;
                    plugin.getLogger().warning(() -> "皮肤获取失败: " + name + "（将使用 Steve 兜底）");
                }
            }
            if (fetched > 0) {
                save();
            }
            if (plugin.getConfigLoader().isDebugGeneral()) {
                int finalFetched = fetched;
                int finalFailed = failed;
                plugin.getLogger().info(() -> String.format(
                        "皮肤预加载完成: 新增 %d, 失败 %d, 总缓存 %d",
                        finalFetched, finalFailed, cache.size()));
            }
        });
    }

    /**
     * 获取指定玩家名的缓存皮肤（主线程安全调用，无阻塞）。
     *
     * @param playerName 玩家名
     * @return 皮肤数据，或 {@code null}（未缓存或获取失败）
     */
    public SimNpc.SkinData getSkin(String playerName) {
        return cache.get(playerName);
    }

    /**
     * 异步保存缓存到磁盘。
     */
    private void save() {
        ensureParentDir();
        TaskUtil.runAsync(plugin, this::doSave);
    }

    /**
     * 实际执行磁盘写入（在异步线程执行）。
     */
    private void doSave() {
        try {
            JsonObject skins = new JsonObject();
            for (var entry : cache.entrySet()) {
                JsonObject skin = new JsonObject();
                skin.addProperty("value", entry.getValue().value());
                if (entry.getValue().signature() != null) {
                    skin.addProperty("signature", entry.getValue().signature());
                }
                skins.add(entry.getKey(), skin);
            }
            JsonObject root = new JsonObject();
            root.add("skins", skins);

            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "皮肤缓存保存失败", e);
        }
    }

    /**
     * 获取当前缓存条目数（调试/统计用）。
     *
     * @return 缓存数量
     */
    public int size() {
        return cache.size();
    }
}
