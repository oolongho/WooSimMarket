package com.oolongho.woosimmarket.npc;

/**
 * NPC 性格数据模型（不可变 record）。
 *
 * <p>每种性格包含 6 个数值维度，在判别式、多次判定、思考展示中
 * 作为权重系数。性格在 NPC spawn 时按 {@link #weight} 加权随机分配，
 * 生命周期内不变，不持久化到磁盘。</p>
 *
 * <p>数据结构与分配机制由子系统 1 定义，判别式由子系统 2 实现、
 * 多次判定由子系统 3 实现、思考展示由子系统 4 实现。</p>
 *
 * <h2>6 个数值维度</h2>
 * <ul>
 *   <li>{@link #priceSensitivity}（价格敏感度倍率，≥0）：
 *       × 全局/物品级 price-sensitivity → 最终价格敏感度。
 *       高=对价格更敏感（提价显著降低购买概率），低=不敏感。</li>
 *   <li>{@link #marketSensitivity}（购买动量敏感度倍率，≥0）：
 *       参与 marketFactor 计算：marketFactor = 1 + momentumStrength × (momentum − 0.5) × 2 × marketSensitivity。
 *       高=跟风购买（热销品更想买），低=独立判断。</li>
 *   <li>{@link #impatience}（冲动度，[0,1]）：
 *       0=极慢决策，1=极快决策。子系统 3 用于决定判定次数与间隔。</li>
 *   <li>{@link #budget}（预算倍率，≥0）：
 *       最多花 {@code budget × standardPrice}，超过此硬上限则购买概率=0。</li>
 *   <li>{@link #weatherSensitivity}（天气敏感度，[0,1]）：
 *       0=不受影响，1=坏天气完全不买。</li>
 *   <li>{@link #timePreference}（时间偏好，[0,1]）：
 *       0=夜型，1=日型，0.5=中性。</li>
 * </ul>
 *
 * <p>非法值由 {@link PersonalityManager#load} 在加载时钳制：
 * {@code impatience}/{@code weatherSensitivity}/{@code timePreference} 钳制到 [0,1]，
 * 其余维度钳制到 ≥0。</p>
 *
 * @param name               性格 key（如 "normal"、"generous"），对应 personalities.yml 中的 key
 * @param weight             spawn 权重（相对值，weight / totalWeight 为分配概率）；≤0 不参与随机
 * @param priceSensitivity   价格敏感度倍率（≥0）
 * @param marketSensitivity  购买动量敏感度倍率（≥0）
 * @param impatience         冲动度（[0,1]）
 * @param budget             预算倍率（≥0）
 * @param weatherSensitivity 天气敏感度（[0,1]）
 * @param timePreference     时间偏好（[0,1]）
 * @author oolongho
 */
public record PersonalityProfile(
        String name,
        double weight,
        double priceSensitivity,
        double marketSensitivity,
        double impatience,
        double budget,
        double weatherSensitivity,
        double timePreference) {
}
