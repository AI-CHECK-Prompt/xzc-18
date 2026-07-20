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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 原始采样点消费 + 衍生指标聚合 + 触发告警/评分
 * 双管道：
 *   1) sample_raw      — 原始波形（高频）
 *   2) sample_metric   — 1min 衍生指标（趋势/告警/评分/回放）
 *
 * 聚合语义（修复后）：
 *   - key = bedId|channel|bucket(分钟)，每 (床,通道,分钟) 一份独立累加器
 *   - flushAgg 只刷新「已闭合」的分钟桶（早于当前分钟），避免把当前还在写入的桶提前清零
 *   - 写完即从 map 移除，自然实现「reset」；Agg 不再无限累计
 */
@Component
public class RawIngestConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawIngestConsumer.class);
    // bucket 在 key 里以 ISO 字符串存储，便于人眼排错；parse 时直接用 ISO_OFFSET_DATE_TIME
    private static final DateTimeFormatter BUCKET_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TimeSeriesDao tsDao;
    private final KafkaTemplate<String, String> kafka;
    private final com.icu.monitor.alert.AlertEngine alertEngine;
    private final com.icu.monitor.scoring.ScoringEngine scoringEngine;

    @Value("${icu.alert.topics.metric}") private String metricTopic;
    @Value("${icu.alert.topics.alert}")  private String alertTopic;

    // 每床每通道每分钟一份聚合缓冲（闭合后由 flushAgg 写库并移除）
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

            // 2) 1 分钟聚合 —— 按采样时间落入对应分钟桶，每桶独立累加，绝不跨分钟复用
            OffsetDateTime bucket = m.getTime().truncatedTo(ChronoUnit.MINUTES);
            String key = aggKey(m, bucket);
            Agg a = aggBuffer.computeIfAbsent(key, k -> new Agg());
            a.feed(m);
            // 每条触发评估（实时告警/评分）
            try { alertEngine.evaluate(m); } catch (Exception e) { log.warn("alert eval err: {}", e.getMessage()); }
            try { scoringEngine.evaluate(m); } catch (Exception e) { log.warn("scoring eval err: {}", e.getMessage()); }
        } catch (Exception e) {
            log.warn("raw ingest parse err: {}", e.getMessage());
        }
    }

    /**
     * 每 30 秒把已闭合的分钟桶刷出：
     *   - 闭合 = bucket.minute < now.minute
     *   - 仍把当前分钟留在内存里继续累计，避免「写到一半清零」造成的均值跳变
     *   - 写库后从 map 移除，等下次同分钟样本到来再 computeIfAbsent 新建（天然 reset）
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public void flushAgg() {
        if (aggBuffer.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime currentBucket = now.truncatedTo(ChronoUnit.MINUTES);
        int flushed = 0, dropped = 0;
        for (Map.Entry<String, Agg> e : aggBuffer.entrySet()) {
            String key = e.getKey();
            String[] parts = key.split("\\|");
            if (parts.length < 3) { aggBuffer.remove(key); dropped++; continue; }
            OffsetDateTime bucket;
            try {
                bucket = OffsetDateTime.parse(parts[2]);
            } catch (Exception parseErr) {
                log.warn("【agg-flush】bad bucket key={} drop", key);
                aggBuffer.remove(key); dropped++; continue;
            }
            if (!bucket.isBefore(currentBucket)) continue; // 当前分钟还在写，跳过

            Agg a = e.getValue();
            Agg.Snapshot snap = a.snapshot();
            // 用 compute 原子地尝试移除；只有真正抢到删除权的才写库，避免和下次 feed 竞争
            Agg removed = aggBuffer.remove(key);
            if (removed == null) continue;
            if (snap.count == 0) { dropped++; continue; }

            try {
                long bedId = Long.parseLong(parts[0]);
                String code = parts[1];
                tsDao.upsertMetricMinute(bucket, 0L, bedId, null,
                    snap.avg, snap.min, snap.max, snap.last, snap.count);
                // 同步发一份到 metric topic，给评分/告警/前端订阅
                String payload = "{\"t\":\"" + BUCKET_FMT.format(bucket) + "\",\"bedId\":" + bedId +
                              ",\"chCode\":\"" + code + "\",\"last\":" + snap.last +
                              ",\"min\":" + snap.min + ",\"max\":" + snap.max +
                              ",\"avg\":" + snap.avg + ",\"count\":" + snap.count + "}";
                kafka.send(metricTopic, String.valueOf(bedId), payload);
                flushed++;
                log.debug("【agg-flush】bed={} ch={} bucket={} count={} avg={} min={} max={} last={}",
                    bedId, code, BUCKET_FMT.format(bucket), snap.count,
                    String.format("%.2f", snap.avg), snap.min, snap.max, snap.last);
            } catch (Exception ex) {
                log.warn("【agg-flush】flush err key={} err={}", key, ex.getMessage());
            }
        }
        if (flushed > 0 || dropped > 0) {
            log.info("【agg-flush】flushed={} dropped={} pending={} currentBucket={}",
                flushed, dropped, aggBuffer.size(), BUCKET_FMT.format(currentBucket));
        }
    }

    private String aggKey(UnifiedMessage m, OffsetDateTime bucket) {
        return m.getBedId() + "|" + m.getChannelCode() + "|" + BUCKET_FMT.format(bucket);
    }

    /**
     * 单分钟桶内的累加器。Kafka concurrency=4，同一 bucket 可能被多线程同时 feed，
     * 因此 feed/snapshot 全部加锁；snapshot 返回不可变快照，调用方据此写库后从外层 map 移除。
     */
    private static class Agg {
        private double sum;
        private double min = Double.MAX_VALUE;
        private double max = -Double.MAX_VALUE;
        private double last;
        private int count;

        synchronized void feed(UnifiedMessage m) {
            double v = m.getValueNum();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            last = v;
            count++;
        }

        synchronized Snapshot snapshot() {
            if (count == 0) return new Snapshot(0, 0d, min, max, last);
            return new Snapshot(count, sum / count, min, max, last);
        }

        static final class Snapshot {
            final int count;
            final double avg, min, max, last;
            Snapshot(int count, double avg, double min, double max, double last) {
                this.count = count; this.avg = avg; this.min = min; this.max = max; this.last = last;
            }
        }
    }
}
