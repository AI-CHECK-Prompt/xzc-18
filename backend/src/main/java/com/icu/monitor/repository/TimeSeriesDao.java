package com.icu.monitor.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 时序库专用 DAO：sample_raw / sample_metric 直接走 JdbcTemplate，绕过 JPA 以获得最佳吞吐。
 * 批量插入用 rewriteBatchedStatements=true 时可达到 5~10w 行/秒。
 */
@Repository
public class TimeSeriesDao {
    private final JdbcTemplate jdbc;
    public TimeSeriesDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertRawBatch(List<Object[]> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(
            "INSERT INTO sample_raw (time, channel_id, bed_id, value_num, value_wav, quality) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            rows);
    }

    public void insertMetricBatch(List<Object[]> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(
            "INSERT INTO sample_metric (time, channel_id, bed_id, patient_id, avg_value, min_value, max_value, last_value, sample_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows);
    }

    /** 衍生指标实时合并：1 分钟聚合写入（用于告警/评分/趋势） */
    public void upsertMetricMinute(OffsetDateTime bucket, long channelId, long bedId, Long patientId,
                                   double avg, double min, double max, double last, int cnt) {
        jdbc.update(
            "INSERT INTO sample_metric (time, channel_id, bed_id, patient_id, avg_value, min_value, max_value, last_value, sample_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (time, channel_id, bed_id) DO UPDATE SET " +
            "  patient_id = COALESCE(EXCLUDED.patient_id, sample_metric.patient_id), " +
            "  avg_value = (sample_metric.avg_value * sample_metric.sample_count + EXCLUDED.avg_value * EXCLUDED.sample_count) " +
            "              / NULLIF(sample_metric.sample_count + EXCLUDED.sample_count, 0), " +
            "  min_value = LEAST(sample_metric.min_value, EXCLUDED.min_value), " +
            "  max_value = GREATEST(sample_metric.max_value, EXCLUDED.max_value), " +
            "  last_value = EXCLUDED.last_value, " +
            "  sample_count = sample_metric.sample_count + EXCLUDED.sample_count",
            Timestamp.from(bucket.toInstant()), channelId, bedId, patientId, avg, min, max, last, cnt);
    }

    public List<Map<String, Object>> queryMetricWindow(long bedId, long channelId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.queryForList(
            "SELECT time, avg_value, min_value, max_value, last_value, sample_count " +
            "FROM sample_metric WHERE bed_id=? AND channel_id=? AND time BETWEEN ? AND ? ORDER BY time",
            bedId, channelId, Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
    }

    public List<Map<String, Object>> queryRawWindow(long bedId, long channelId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return jdbc.queryForList(
            "SELECT time, value_num, quality FROM sample_raw " +
            "WHERE bed_id=? AND channel_id=? AND time BETWEEN ? AND ? " +
            "ORDER BY time DESC LIMIT ?",
            bedId, channelId, Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()), limit);
    }

    public Map<String, Object> latestMetric(long bedId, long channelId) {
        return jdbc.queryForMap(
            "SELECT time, last_value, avg_value FROM sample_metric " +
            "WHERE bed_id=? AND channel_id=? ORDER BY time DESC LIMIT 1",
            bedId, channelId);
    }

    /** 写入评分结果（hypertable） */
    public void insertScoringResult(OffsetDateTime time, long patientId, Long bedId, String ruleCode,
                                    double score, String level, String detailJson) {
        jdbc.update(
            "INSERT INTO scoring_result (time, patient_id, bed_id, rule_code, score, level, detail) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
            Timestamp.from(time.toInstant()), patientId, bedId, ruleCode, score, level, detailJson);
    }

    public List<Map<String, Object>> recentScoring(long patientId, String ruleCode, int limit) {
        return jdbc.queryForList(
            "SELECT time, score, level, detail FROM scoring_result " +
            "WHERE patient_id=? AND rule_code=? ORDER BY time DESC LIMIT ?",
            patientId, ruleCode, limit);
    }
}
