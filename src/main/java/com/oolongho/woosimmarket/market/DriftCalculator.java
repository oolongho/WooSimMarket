package com.oolongho.woosimmarket.market;

import com.oolongho.woosimmarket.config.ConfigLoader.DriftConfig;
import com.oolongho.woosimmarket.database.DatabaseManager.PurchaseLogRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 标准价漂移纯计算器（无状态，线程安全）。
 *
 * <p>根据近期 purchase_log 记录算出新的 drift 乘数。公式：
 * <pre>
 * effStd       = stdPrice × currentDrift
 * M            = 近期 bought=1 记录的 price 中位数（无 bought=1 时取 effStd → priceDev=0）
 * R            = count(bought=1) / count(all)
 * priceDev     = M / effStd − 1
 * supplySignal = (R − 0.5) × 2
 * adjustment   = priceWeight × priceDev
 *             + supplyWeight × supplySignal
 *             + meanReversion × (1.0 − currentDrift)
 * adjustment   = clamp(adjustment, −dailyCap, +dailyCap)
 * newDrift     = clamp(currentDrift × (1 + adjustment), minFactor, maxFactor)
 * </pre>
 *
 * <p>空样本（records 为空）返回 currentDrift 不变，避免无数据噪声。</p>
 *
 * @author oolongho
 */
public final class DriftCalculator {

    private DriftCalculator() {
    }

    /**
     * 计算新的 drift 乘数。
     *
     * @param currentDrift 当前 drift（初始 1.0）
     * @param stdPrice     items.yml 静态锚价
     * @param records      近期 purchase_log 记录（含 bought=0/1，按 itemId 已过滤）
     * @param cfg          漂移配置参数
     * @return 新 drift 乘数
     */
    public static double computeDrift(double currentDrift, double stdPrice,
                                      List<PurchaseLogRecord> records, DriftConfig cfg) {
        if (records == null || records.isEmpty()) {
            return currentDrift;
        }

        double effStd = stdPrice * currentDrift;

        // 中位数 M：仅 bought=1 的 price
        List<Double> boughtPrices = new ArrayList<>();
        int boughtCount = 0;
        for (PurchaseLogRecord r : records) {
            if (r.bought()) {
                boughtPrices.add(r.price());
                boughtCount++;
            }
        }
        double m = boughtPrices.isEmpty() ? effStd : median(boughtPrices);

        // 购买率 R
        double r = (double) boughtCount / records.size();

        // 合成 adjustment
        double priceDev = m / effStd - 1.0;
        double supplySignal = (r - 0.5) * 2.0;
        double adjustment = cfg.priceWeight() * priceDev
                + cfg.supplyWeight() * supplySignal
                + cfg.meanReversion() * (1.0 - currentDrift);

        // 日幅度钳制
        adjustment = Math.max(-cfg.dailyCap(), Math.min(cfg.dailyCap(), adjustment));

        // 应用 + 总幅度钳制
        double newDrift = currentDrift * (1.0 + adjustment);
        return Math.max(cfg.minFactor(), Math.min(cfg.maxFactor(), newDrift));
    }

    /** 取列表中位数（排序后取中位，偶数取两中位均值）。 */
    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
