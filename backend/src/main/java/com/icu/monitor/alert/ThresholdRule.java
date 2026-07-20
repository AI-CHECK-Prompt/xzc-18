package com.icu.monitor.alert;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 阈值规则（按指标）
 * INFO  : 提示   — 偏离正常
 * WARN  : 警告   — 临床需要关注
 * CRIT  : 危急   — 立即处置
 */
@Data
public class ThresholdRule {
    private String channel;       // HR / SPO2 / SBP / DBP / TEMP / RR
    private Double critLow, warnLow, infoLow;
    private Double infoHigh, warnHigh, critHigh;
    private Integer minQuality = 30;  // 信号质量低于此值视为设备异常

    public String classify(double v) {
        if (critLow  != null && v <= critLow)  return "CRITICAL";
        if (critHigh != null && v >= critHigh) return "CRITICAL";
        if (warnLow  != null && v <= warnLow)  return "WARN";
        if (warnHigh != null && v >= warnHigh) return "WARN";
        if (infoLow  != null && v <= infoLow)  return "INFO";
        if (infoHigh != null && v >= infoHigh) return "INFO";
        return null;
    }

    public static Map<String, ThresholdRule> defaults() {
        Map<String, ThresholdRule> m = new HashMap<>();
        // HR（次/分）
        m.put("HR", rule("HR", 40.0, 50.0, 55.0, 120.0, 130.0, 150.0));
        // SPO2（%）
        m.put("SPO2", rule("SPO2", 85.0, 90.0, 92.0, null, null, null));
        // SBP（mmHg）
        m.put("SBP", rule("SBP", 80.0, 90.0, 100.0, 180.0, 200.0, 220.0));
        // DBP
        m.put("DBP", rule("DBP", 40.0, 50.0, 60.0, 100.0, 110.0, 120.0));
        // TEMP（℃）
        m.put("TEMP", rule("TEMP", 35.0, 36.0, null, 38.5, 39.0, 40.0));
        // RR（次/分）
        m.put("RR", rule("RR", 8.0, 10.0, 12.0, 24.0, 28.0, 32.0));
        return m;
    }

    private static ThresholdRule rule(String ch, Double cl, Double wl, Double il, Double ih, Double wh, Double chh) {
        ThresholdRule r = new ThresholdRule();
        r.setChannel(ch);
        r.setCritLow(cl); r.setWarnLow(wl); r.setInfoLow(il);
        r.setInfoHigh(ih); r.setWarnHigh(wh); r.setCritHigh(chh);
        return r;
    }
}
