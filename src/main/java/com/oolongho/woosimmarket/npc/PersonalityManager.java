package com.oolongho.woosimmarket.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * NPC 性格管理器 —— 加载 npc.yml 的 personalities 节、加权随机分配性格。
 *
 * <p>线程模型：{@link #load} 和 {@link #reload} 在主线程 onEnable/reload 时调用，
 * {@link #random} 由 {@link NpcManager#trySpawnNpc} 在主线程 spawn 任务中调用。
 * {@link ConcurrentHashMap} 保证可见性，{@link #profiles} 列表在 random 时只读。</p>
 *
 * <p>加载策略：解析 personalities 节点下每个 key，钳制非法维度值，跳过 weight ≤0 的性格。
 * 维度缺失时使用默认值（不阻塞加载）。无任何性格时 {@link #random} 返回硬编码 normal 兜底。</p>
 *
 * <h2>默认维度值（缺失时使用）</h2>
 * <ul>
 *   <li>price-sensitivity = 1.0</li>
 *   <li>market-sensitivity = 1.0</li>
 *   <li>impatience = 0.5</li>
 *   <li>budget = 3.0</li>
 *   <li>weather-sensitivity = 0.3</li>
 *   <li>time-preference = 0.5</li>
 * </ul>
 *
 * @author oolongho
 */
public class PersonalityManager {

    // 默认维度值（缺失字段使用）
    private static final double DEFAULT_PRICE_SENSITIVITY = 1.0;
    private static final double DEFAULT_MARKET_SENSITIVITY = 1.0;
    private static final double DEFAULT_IMPATIENCE = 0.5;
    private static final double DEFAULT_BUDGET = 3.0;
    private static final double DEFAULT_WEATHER_SENSITIVITY = 0.3;
    private static final double DEFAULT_TIME_PREFERENCE = 0.5;

    /** 硬编码 normal 兜底（无任何性格时返回此值，不抛异常）。权重镜像 npc.yml 的 normal.weight=10 保持语义一致。 */
    private static final PersonalityProfile NORMAL_FALLBACK = new PersonalityProfile(
            "normal", 10.0,
            DEFAULT_PRICE_SENSITIVITY, DEFAULT_MARKET_SENSITIVITY,
            DEFAULT_IMPATIENCE, DEFAULT_BUDGET,
            DEFAULT_WEATHER_SENSITIVITY, DEFAULT_TIME_PREFERENCE);

    /** 性格 key → profile，加载后只读访问。 */
    private final Map<String, PersonalityProfile> profileMap = new ConcurrentHashMap<>();

    /** profile 列表（random 时遍历），与 profileMap 同步。 */
    private volatile List<PersonalityProfile> profiles = Collections.emptyList();

    /** 总权重（random 时归一化用），与 profiles 同步。 */
    private volatile double totalWeight = 0.0;

    /**
     * 加载 personalities 配置，构建 profile map 与总权重。
     *
     * <p>重复调用前应通过 {@link #reload} 清空旧数据。本方法不清空状态，
     * 直接覆盖（用于初始化场景）。reload 时调用 {@link #reload}。</p>
     *
     * <p>钳制规则：
     * <ul>
     *   <li>impatience / weather-sensitivity / time-preference 钳制到 [0, 1]</li>
     *   <li>price-sensitivity / market-sensitivity / budget 钳制到 ≥0</li>
     *   <li>weight ≤0 的性格跳过（不参与随机）</li>
     *   <li>维度缺失时使用默认值</li>
     * </ul>
     *
     * @param config npc.yml 配置
     */
    public void load(FileConfiguration config) {
        if (config == null) {
            return;
        }
        ConfigurationSection root = config.getConfigurationSection("personalities");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            double weight = sec.getDouble("weight", 0.0);
            if (weight <= 0.0) {
                // weight ≤0 跳过（不参与随机）
                continue;
            }
            PersonalityProfile profile = new PersonalityProfile(
                    key,
                    weight,
                    Math.max(0.0, sec.getDouble("price-sensitivity", DEFAULT_PRICE_SENSITIVITY)),
                    Math.max(0.0, sec.getDouble("market-sensitivity", DEFAULT_MARKET_SENSITIVITY)),
                    clamp01(sec.getDouble("impatience", DEFAULT_IMPATIENCE)),
                    Math.max(0.0, sec.getDouble("budget", DEFAULT_BUDGET)),
                    clamp01(sec.getDouble("weather-sensitivity", DEFAULT_WEATHER_SENSITIVITY)),
                    clamp01(sec.getDouble("time-preference", DEFAULT_TIME_PREFERENCE)));
            profileMap.put(key, profile);
        }
        // 同步快照（不可变列表，random 只读）
        profiles = Collections.unmodifiableList(new ArrayList<>(profileMap.values()));
        totalWeight = profiles.stream().mapToDouble(PersonalityProfile::weight).sum();
    }

    /**
     * 加权随机返回一种性格。
     *
     * <p>按各性格 {@code weight / totalWeight} 概率返回。无任何性格时返回硬编码
     * normal 兜底（不抛异常），保证 NpcManager.trySpawnNpc 流程不中断。</p>
     *
     * @return 随机性格；无性格时返回 normal 兜底
     */
    public PersonalityProfile random() {
        List<PersonalityProfile> list = profiles;
        if (list.isEmpty() || totalWeight <= 0.0) {
            return NORMAL_FALLBACK;
        }
        double r = ThreadLocalRandom.current().nextDouble(totalWeight);
        double acc = 0.0;
        for (PersonalityProfile p : list) {
            acc += p.weight();
            if (r < acc) {
                return p;
            }
        }
        // 浮点误差兜底：返回最后一个
        return list.get(list.size() - 1);
    }

    /**
     * 重载：清空 map 后重新加载。
     *
     * @param config npc.yml 配置
     */
    public void reload(FileConfiguration config) {
        profileMap.clear();
        profiles = Collections.emptyList();
        totalWeight = 0.0;
        load(config);
    }

    /**
     * 获取指定 key 的性格（用于查询单元测试 / 调试）。
     *
     * @param key 性格 key
     * @return 性格；不存在返回 null
     */
    public PersonalityProfile get(String key) {
        return profileMap.get(key);
    }

    /**
     * 已加载性格数量。
     *
     * @return 性格数量
     */
    public int size() {
        return profiles.size();
    }

    /**
     * 将 value 钳制到 [0, 1] 区间。
     */
    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
