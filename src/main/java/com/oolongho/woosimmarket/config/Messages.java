package com.oolongho.woosimmarket.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.oolongho.woosimmarket.WooSimMarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 消息管理器
 * 负责加载 lang/*.yml 并以 MiniMessage 解析为 Adventure Component。
 *
 * <p>风格参考 WooHolograms Messages：{@link ConcurrentHashMap} 缓存 +
 * {@link #loadLanguage()} 释放默认 + {@link InputStreamReader}(UTF_8) 设 defaults。
 * 区别在于用 {@link MiniMessage} 替代纯字符串拼接，向 Audience 发送 Component。</p>
 *
 * <p>占位符采用 {@code {xxx}} 风格（与 WooHolograms 一致），替换为字面值后再由
 * MiniMessage 解析；注意替换值中的 MiniMessage 标签会被解析，如需字面显示请转义。</p>
 *
 * <p>{@code {prefix}} 占位符策略：由 {@link #get(String, String...)} 自动替换为 lang 中
 * {@code prefix} 键值，调用方无需拼接。命令反馈消息体应显式书写 {@code {prefix}}；
 * GUI 标题与被动通知不应包含该占位符。{@link #getWithPrefix(String, String...)} 已
 * 标记 {@link Deprecated}，仅为向后兼容保留，内部直接委托 {@link #get}。</p>
 *
 * @author oolongho
 */
public class Messages {

    /** prefix 占位符，写在消息体中由 get 自动替换 */
    private static final String PREFIX_PLACEHOLDER = "{prefix}";

    /** prefix 键名，用于获取前缀值（即使 replacements 中传 prefix 也会被忽略） */
    private static final String PREFIX_KEY = "prefix";

    private final WooSimMarket plugin;
    private final MiniMessage miniMessage;
    private final Map<String, String> messages;
    private FileConfiguration langConfig;

    public Messages(WooSimMarket plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.messages = new ConcurrentHashMap<>();
    }

    /**
     * 初始化消息：释放并加载当前语言文件。
     */
    public void initialize() {
        loadLanguage();
    }

    /**
     * 加载语言文件：优先使用 config 中 settings.language 指定的语言，
     * 缺失时回退到 zh-CN；同时把 jar 内 zh-CN.yml 作为 defaults 兜底键。
     */
    private void loadLanguage() {
        String language = plugin.getConfig().getString("settings.language", "zh-CN");

        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // 始终释放 zh-CN 作为兜底
        File defaultLangFile = new File(langFolder, "zh-CN.yml");
        if (!defaultLangFile.exists() && resourceExists("lang/zh-CN.yml")) {
            plugin.saveResource("lang/zh-CN.yml", false);
        }

        File langFile = new File(langFolder, language + ".yml");
        if (!langFile.exists()) {
            String resourcePath = "lang/" + language + ".yml";
            if (resourceExists(resourcePath)) {
                plugin.saveResource(resourcePath, false);
            } else {
                plugin.getLogger().warning(() -> "语言文件 " + language + ".yml 不存在，回退到 zh-CN");
                langFile = defaultLangFile;
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // jar 内 zh-CN.yml 作为 defaults，避免用户删键导致消息缺失
        // try-with-resources 确保 InputStream/Reader 关闭（loadConfiguration 不负责关闭）
        try (InputStream defaultStream = plugin.getResource("lang/zh-CN.yml");
             InputStreamReader reader = defaultStream == null ? null
                     : new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
            if (reader != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(reader);
                langConfig.setDefaults(defaultConfig);
            }
        } catch (IOException e) {
            plugin.getLogger().warning(() -> "无法加载内置默认语言文件: " + e.getMessage());
        }

        loadMessages();
    }

    /**
     * 将所有字符串叶子节点加载到内存缓存。
     *
     * <p>用 {@code getString(key) != null} 过滤 section 节点（section 返回 null），
     * 支持 zh-CN.yml 的扁平键结构。</p>
     */
    private void loadMessages() {
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            String value = langConfig.getString(key);
            if (value != null) {
                messages.put(key, value);
            }
        }
    }

    /**
     * 检查 jar 内是否存在指定资源（确保 InputStream 关闭，避免资源泄漏）。
     *
     * @param path 资源路径
     * @return 存在返回 true
     */
    private boolean resourceExists(String path) {
        try (InputStream is = plugin.getResource(path)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取原始消息字符串（未经 MiniMessage 解析），适用于 PlaceholderAPI / 日志场景。
     *
     * @param key 消息键
     * @return 原始消息；键不存在则返回 key 本身
     */
    public String getRaw(String key) {
        String message = messages.get(key);
        if (message == null) {
            message = langConfig.getString(key);
        }
        return message != null ? message : key;
    }

    /**
     * 获取消息并解析为 Component。自动替换 {@code {prefix}} 占位符为 lang 中
     * {@code prefix} 键值。
     *
     * @param key 消息键
     * @return 解析后的 Component；键不存在则返回包含 key 的字面 Component
     */
    public Component get(String key) {
        return parse(replace(getRaw(key)));
    }

    /**
     * @deprecated 已由消息体 {@code {prefix}} 占位符替代，直接调用 {@link #get(String)} 即可。
     *
     * @param key 消息键
     * @return 解析后的 Component
     */
    @Deprecated
    public Component getWithPrefix(String key) {
        return get(key);
    }

    /**
     * 获取消息，按 {@code {xxx}} 占位符替换为字面值后解析为 Component。
     *
     * <p>{@code {prefix}} 自动替换为 lang 中 {@code prefix} 键值，其余 {@code {xxx}}
     * 由 replacements 替换。replacements 为键值对序列：
     * {@code {"item", "钻石", "price", "100"}}。</p>
     *
     * @param key 消息键
     * @param replacements 占位符键值对
     * @return 替换并解析后的 Component
     */
    public Component get(String key, String... replacements) {
        return parse(replace(getRaw(key), replacements));
    }

    /**
     * @deprecated 已由消息体 {@code {prefix}} 占位符替代，直接调用
     * {@link #get(String, String...)} 即可。
     *
     * @param key 消息键
     * @param replacements 占位符键值对
     * @return 替换并解析后的 Component
     */
    @Deprecated
    public Component getWithPrefix(String key, String... replacements) {
        return get(key, replacements);
    }

    /**
     * 替换 {@code {prefix}} 占位符为 lang 中 {@code prefix} 键值，再替换
     * replacements 中的其他 {@code {xxx}} 占位符为字面值。
     *
     * <p>规则：
     * <ul>
     *   <li>replacements 中传 {@code prefix} 键会被忽略并打印告警（{prefix} 是保留占位符）</li>
     *   <li>{@code null} 键或值会被安全处理（键跳过，值替换为空串）</li>
     *   <li>消息体无 {@code {prefix}} 时此步骤为 no-op，GUI 标题调用安全</li>
     * </ul>
     *
     * @param message 原始消息
     * @param replacements 占位符键值对
     * @return 替换后的消息
     */
    private String replace(String message, String... replacements) {
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                if (placeholder == null) continue;
                if (PREFIX_KEY.equalsIgnoreCase(placeholder)) {
                    plugin.getLogger().warning(
                            "Messages.get('" + placeholder + "') 被显式传入 prefix 替换值，已忽略。"
                                    + "请在消息体中使用 {prefix} 占位符，不要在代码中传 prefix。");
                    continue;
                }
                message = message.replace("{" + placeholder + "}", value == null ? "" : value);
            }
        }
        // 自动替换 {prefix}（即使 replacements 未传 prefix）
        message = message.replace(PREFIX_PLACEHOLDER, getRaw(PREFIX_KEY));
        return message;
    }

    /**
     * 用 MiniMessage 解析字符串为 Component。解析失败时降级为字面文本并告警。
     */
    private Component parse(String raw) {
        try {
            return miniMessage.deserialize(raw);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(() -> "消息解析失败，降级为字面文本: " + ex.getMessage());
            return Component.text(raw);
        }
    }

    /**
     * 重载语言文件。
     */
    public void reload() {
        loadLanguage();
    }

    /**
     * 向玩家发送消息。消息体应显式包含 {@code {prefix}} 占位符以携带前缀。
     *
     * @param player 玩家
     * @param key    消息键
     */
    public void send(Player player, String key) {
        player.sendMessage(get(key));
    }

    /**
     * 向玩家发送消息，并替换占位符。消息体应显式包含 {@code {prefix}} 占位符以携带前缀。
     *
     * @param player        玩家
     * @param key           消息键
     * @param replacements 占位符键值对
     */
    public void send(Player player, String key, String... replacements) {
        player.sendMessage(get(key, replacements));
    }

    /**
     * 向命令发送者（可以是控制台或玩家）发送消息。
     *
     * <p>Paper 的 {@link CommandSender} 实现了 {@code Audience} 接口，
     * 可直接发送 Adventure Component。消息体应显式包含 {@code {prefix}} 占位符以携带前缀。</p>
     *
     * @param sender        发送者
     * @param key           消息键
     */
    public void send(CommandSender sender, String key) {
        sender.sendMessage(get(key));
    }

    /**
     * 向命令发送者发送消息，并替换占位符。消息体应显式包含 {@code {prefix}} 占位符以携带前缀。
     *
     * @param sender        发送者
     * @param key           消息键
     * @param replacements 占位符键值对
     */
    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(get(key, replacements));
    }
}
