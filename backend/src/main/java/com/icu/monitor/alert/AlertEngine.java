package com.icu.monitor.alert;

import com.icu.monitor.domain.AlertEvent;
import com.icu.monitor.protocol.UnifiedMessage;
import com.icu.monitor.push.NurseStationPusher;
import com.icu.monitor.repository.AlertEventRepo;
import com.icu.monitor.repository.AlertEscalationPolicyRepo;
import com.icu.monitor.repository.TimeSeriesDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 早期预警引擎。
 *
 * 解决会议中提到的"心率探头脱落持续触发假性低值"事故：
 *  1) 阈值规则 — 数值越界产生 AlertSignal
 *  2) 智能静默 — 多指标交叉验证 + 信号质量判断
 *  3) 去重 — 同患者同类型告警在 dedup_window_sec 内只触发一次
 *  4) 升级 — 连续 escalation_count 次触发后升级
 *  5) 推送 — WebSocket + Kafka
 *
 * 端到端延迟目标：< 3s
 */
@Service
public class AlertEngine {
    private static final Logger log = LoggerFactory.getLogger("【ALERT】");

    private final Map<String, ThresholdRule> rules = ThresholdRule.defaults();
    private final ConcurrentHashMap<String, DedupState> dedup = new ConcurrentHashMap<>();
    private final AlertEventRepo alertRepo;
    private final AlertEscalationPolicyRepo policyRepo;
    private final TimeSeriesDao tsDao;
    private final NurseStationPusher pusher;
    private final KafkaTemplate<String, String> kafka;

    @Value("${icu.alert.topics.alert}") private String alertTopic;
    @Value("${icu.alert.dedup-window-sec:60}") private int defaultDedupSec;
    @Value("${icu.alert.escalation-threshold:3}") private int defaultEscalationCount;
    // CRITICAL 级别去重窗口默认与回放窗口一致：保证一次抢救事件全程只产生 1 个告警 ID
    @Value("${icu.alert.playback-window-min:30}") private int criticalDedupWindowMin;

    // 床位最近 30s 内同患者其它通道滑动窗（用于交叉验证）
    private final ConcurrentHashMap<Long, RecentVitals> recentVitals = new ConcurrentHashMap<>();

    public AlertEngine(AlertEventRepo alertRepo,
                       AlertEscalationPolicyRepo policyRepo,
                       TimeSeriesDao tsDao,
                       NurseStationPusher pusher,
                       KafkaTemplate<String, String> kafka) {
        this.alertRepo = alertRepo;
        this.policyRepo = policyRepo;
        this.tsDao = tsDao;
        this.pusher = pusher;
        this.kafka = kafka;
    }

    public void evaluate(UnifiedMessage m) {
        ThresholdRule rule = rules.get(m.getChannelCode());
        if (rule == null) return;
        String level = rule.classify(m.getValueNum());
        if (level == null) return;                                // 正常范围，不告警

        // 1) 智能静默：低质量信号 + 多指标交叉验证 → 抑制
        RecentVitals rv = recentVitals.computeIfAbsent(m.getBedId(), k -> new RecentVitals());
        rv.feed(m);
        if (shouldSilence(m, rule, level, rv)) {
            log.info("suppress: bed={} ch={} v={} q={} (likely device artifact)",
                m.getBedId(), m.getChannelCode(), m.getValueNum(), m.getQuality());
            return;
        }

        // 2) 去重 + 升级
        String alertType = alertTypeFor(m.getChannelCode(), m.getValueNum(), ruleDirection(m, level));
        String key = m.getBedId() + "|" + m.getChannelCode() + "|" + alertType;
        DedupState s = dedup.computeIfAbsent(key, k -> new DedupState());
        AlertEvent ae = decideAndPersist(s, m, level);

        if (ae != null) {
            pusher.push(ae);
            try { kafka.send(alertTopic, ae.getBedId().toString(),
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(ae)); } catch (Exception ignore) {}
            log.info("alert: bed={} ch={} type={} level={} v={} status={} dedup={}",
                ae.getBedId(), ae.getChannelCode(), ae.getAlertType(), ae.getLevel(),
                ae.getValue(), ae.getStatus(), ae.getDedupCount());
        }
    }

    /**
     * 智能静默：
     *  - 信号质量 < 30 视为设备异常
     *  - 单指标异常 + 其它关键指标（SPO2、RR）正常 → 设备异常
     *  - 极端值（如 HR=0）但 SpO2 正常 → 设备异常
     */
    private boolean shouldSilence(UnifiedMessage m, ThresholdRule rule, String level, RecentVitals rv) {
        if (m.getQuality() != null && m.getQuality() < rule.getMinQuality()) return true;
        if ("HR".equals(m.getChannelCode()) && m.getValueNum() != null && m.getValueNum() <= 5.0) {
            Double spo2 = rv.recent("SPO2");
            if (spo2 != null && spo2 >= 90.0) return true;        // 心率为 0 但血氧正常 → 探头脱落
        }
        if ("SPO2".equals(m.getChannelCode()) && m.getValueNum() != null && m.getValueNum() <= 50.0) {
            Double hr = rv.recent("HR");
            Double rr = rv.recent("RR");
            if ((hr == null || hr >= 50) && (rr == null || rr >= 10)) return true; // 血氧 0 但生命体征正常
        }
        return false;
    }

    private AlertEvent decideAndPersist(DedupState s, UnifiedMessage m, String level) {
        OffsetDateTime now = m.getTime();
        // CRITICAL 使用更大的去重窗口（与回放窗口一致），避免一次抢救事件被切分成多个告警 ID
        long windowSec = "CRITICAL".equals(level)
            ? Math.max(defaultDedupSec, (long) criticalDedupWindowMin * 60)
            : defaultDedupSec;
        // 取医院策略
        if (m.getPatientId() != null) {
            // 简化：全部用默认策略；生产可按 hospitalId 加载
        }
        if (s.lastId != null && s.lastTime != null
            && Duration.between(s.lastTime, now).getSeconds() <= windowSec) {
            // 去重窗口内：累加计数；若超过升级阈值则升级
            s.count++;
            AlertEvent existing = alertRepo.findById(s.lastId).orElse(null);
            if (existing == null) return null;
            existing.setDedupCount(s.count);
            if (s.count >= defaultEscalationCount
                && "WARN".equals(existing.getLevel())
                && !"CRITICAL".equals(level)) {
                existing.setLevel("CRITICAL");
                existing.setMessage(existing.getMessage() + "（已升级）");
            } else if ("CRITICAL".equals(existing.getLevel())) {
                // 已经是 CRITICAL，仅累加计数，不重复推 Kafka，否则回放服务会重复建会话
                alertRepo.save(existing);
                return null;
            } else {
                return null;                                       // 静默，不推送
            }
            alertRepo.save(existing);
            return existing;
        }

        // 新告警
        AlertEvent ae = new AlertEvent();
        ae.setTime(now);
        ae.setBedId(m.getBedId());
        ae.setPatientId(m.getPatientId());
        ae.setChannelCode(m.getChannelCode());
        ae.setLevel(level);
        ae.setAlertType(alertType);
        ae.setValue(m.getValueNum());
        ae.setMessage(buildMessage(ae));
        ae.setStatus("OPEN");
        ae.setDedupCount(1);
        ae.setCreatedAt(now);
        alertRepo.save(ae);
        s.lastId = ae.getId();
        s.lastTime = now;
        s.count = 1;
        return ae;
    }

    private String alertTypeFor(String ch, double v, String dir) {
        if ("LOW".equals(dir))  return ch + "_LOW";
        if ("HIGH".equals(dir)) return ch + "_HIGH";
        return ch + "_ABNORMAL";
    }

    private String ruleDirection(UnifiedMessage m, String level) {
        ThresholdRule r = rules.get(m.getChannelCode());
        if (r == null) return "ABNORMAL";
        if (r.getCritLow() != null && m.getValueNum() <= r.getCritLow()) return "LOW";
        if (r.getWarnLow() != null && m.getValueNum() <= r.getWarnLow()) return "LOW";
        if (r.getInfoLow() != null && m.getValueNum() <= r.getInfoLow()) return "LOW";
        return "HIGH";
    }

    private String buildMessage(AlertEvent ae) {
        return ae.getChannelCode() + " " + ("CRITICAL".equals(ae.getLevel()) ? "危急" :
                                            "WARN".equals(ae.getLevel()) ? "警告" : "提示")
             + "：当前值 " + ae.getValue();
    }

    private static class DedupState {
        Long lastId;
        OffsetDateTime lastTime;
        int count;
    }

    private static class RecentVitals {
        final Map<String, Double> last = new HashMap<>();
        final Map<String, OffsetDateTime> lastT = new HashMap<>();
        synchronized void feed(UnifiedMessage m) {
            last.put(m.getChannelCode(), m.getValueNum());
            lastT.put(m.getChannelCode(), m.getTime());
        }
        Double recent(String ch) {
            OffsetDateTime t = lastT.get(ch);
            if (t == null) return null;
            if (Duration.between(t, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds() > 30) return null;
            return last.get(ch);
        }
    }
}
