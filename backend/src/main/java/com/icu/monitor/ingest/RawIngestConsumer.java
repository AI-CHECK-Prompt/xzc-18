package com.icu.monitor.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icu.monitor.protocol.UnifiedMessage;
import com.icu.monitor.repository.TimeSeriesDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 原始采样点消费 + 衍生指标聚合 + 触发告警/评分
 * 双管道：
 *   1) sample_raw      — 原始波形（高频）
 *   2) sample_metric   — 1min 衍生指标（趋势/告警/评分/回放）
 */
@Component
public class RawIngestConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawIngestConsumer.class);

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TimeSeriesDao tsDao;
    private final KafkaTemplate<String, String> kafka;
    private final com.icu.monitor.alert.AlertEngine alertEngine;
    private final com.icu.monitor.scoring.ScoringEngine scoringEngine;

    @Value("${icu.alert.topics.metric}") private String metricTopic;
    @Value("${icu.alert.topics.alert}")  private String alertTopic;

    // 每床每通道 1 分钟聚合缓冲
    private final ConcurrentHashMap<String, Agg> aggBuffer = new ConcurrentHashMap<>();

    public RawIngestConsumer(TimeSeriesDao tsDao,
                             KafkaTemplate<String, String> kafka,
                             com.icu.monitor.alert.AlertEngine alertEngine,
                             com.icu.monitor.scoring.ScoringEngine scoringEngine) {
        this.tsDao = tsDao;
        this.kafka = kafka;
        this.alertEngine = alertEngine;
        this.scoringEngine = scoringEngine;
    }

    @KafkaListener(topics = "${icu.alert.topics.raw}", concurrency = "4")
    public void onMessage(String payload) {
        try {
            UnifiedMessage m = json.readValue(payload, UnifiedMessage.class);
            if (m.getBedId() == null || m.getChannelCode() == null || m.getValueNum() == null) return;
            // 1) 原始波形
            List<Object[]> row = new ArrayList<>(1);
            row.add(new Object[] {
                Timestamp.from(m.getTime().toInstant()),
                0L,                                    // channel_id（演示用 0；生产应由 channel 映射填充）
                m.getBedId(),
                m.getValueNum(),
                null,
                m.getQuality() == null ? 100 : m.getQuality()
            });
            tsDao.insertRawBatch(row);

            // 2) 1 分钟聚合
            String key = aggKey(m);
            Agg a = aggBuffer.computeIfAbsent(key, k -> new Agg());
            a.feed(m);
            // 每条触发评估（实时告警/评分）
            try { alertEngine.evaluate(m); } catch (Exception e) { log.warn("alert eval err: {}", e.getMessage()); }
            try { scoringEngine.evaluate(m); } catch (Exception e) { log.warn("scoring eval err: {}", e.getMessage()); }
        } catch (Exception e) {
            log.warn("raw ingest parse err: {}", e.getMessage());
        }
    }

    /** 每 30 秒把聚合缓冲刷出 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public void flushAgg() {
        if (aggBuffer.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime bucket = now.truncatedTo(ChronoUnit.MINUTES);
        for (Map.Entry<String, Agg> e : aggBuffer.entrySet()) {
            Agg a = e.getValue();
            a.snapshotAndReset();
            if (a.count == 0) continue;
            String[] parts = e.getKey().split("\\|");
            try {
                long bedId = Long.parseLong(parts[0]);
                String code = parts[1];
                tsDao.upsertMetricMinute(bucket, 0L, bedId, null,
                    a.sum / a.count, a.min, a.max, a.last, a.count);
                // 同步发一份到 metric topic，给评分/告警/前端订阅
                String json = "{\"t\":\"" + bucket + "\",\"bedId\":" + bedId +
                              ",\"chCode\":\"" + code + "\",\"last\":" + a.last +
                              ",\"min\":" + a.min + ",\"max\":" + a.max + "}";
                kafka.send(metricTopic, String.valueOf(bedId), json);
            } catch (Exception ex) {
                log.warn("flush agg err: {}", ex.getMessage());
            }
        }
    }

    private String aggKey(UnifiedMessage m) {
        return m.getBedId() + "|" + m.getChannelCode();
    }

    private static class Agg {
        double sum, min = Double.MAX_VALUE, max = -Double.MAX_VALUE, last;
        int count;
        void feed(UnifiedMessage m) {
            double v = m.getValueNum();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            last = v;
            count++;
        }
        void snapshotAndReset() { /* 已被外部读取；下一次 feed 自动覆盖 */ }
    }
}
