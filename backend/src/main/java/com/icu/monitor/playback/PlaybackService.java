package com.icu.monitor.playback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icu.monitor.domain.AlertEvent;
import com.icu.monitor.domain.PlaybackItem;
import com.icu.monitor.domain.PlaybackSession;
import com.icu.monitor.repository.AlertEventRepo;
import com.icu.monitor.repository.NursingRecordRepo;
import com.icu.monitor.repository.OrderExecutionRepo;
import com.icu.monitor.repository.PlaybackItemRepo;
import com.icu.monitor.repository.PlaybackSessionRepo;
import com.icu.monitor.repository.TimeSeriesDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 抢救事件回放服务。
 * 监听 alert 事件，对 CRITICAL 告警自动建立 30 分钟回放会话（前后各 ICU_PLAYBACK_WINDOW_MIN）。
 * 把触发前后 30 分钟内的波形/体征/告警/医嘱/护理按时间轴串联展示。
 */
@Service
public class PlaybackService {
    private static final Logger log = LoggerFactory.getLogger("【PLAYBACK】");
    private final ObjectMapper json = new ObjectMapper();
    private final AlertEventRepo alertRepo;
    private final PlaybackSessionRepo sessionRepo;
    private final PlaybackItemRepo itemRepo;
    private final NursingRecordRepo nursingRepo;
    private final OrderExecutionRepo orderRepo;
    private final TimeSeriesDao tsDao;

    @Value("${icu.alert.playback-window-min:30}") private int windowMin;

    public PlaybackService(AlertEventRepo alertRepo,
                           PlaybackSessionRepo sessionRepo,
                           PlaybackItemRepo itemRepo,
                           NursingRecordRepo nursingRepo,
                           OrderExecutionRepo orderRepo,
                           TimeSeriesDao tsDao) {
        this.alertRepo = alertRepo;
        this.sessionRepo = sessionRepo;
        this.itemRepo = itemRepo;
        this.nursingRepo = nursingRepo;
        this.orderRepo = orderRepo;
        this.tsDao = tsDao;
    }

    @KafkaListener(topics = "${icu.alert.topics.alert}", concurrency = "1", groupId = "playback")
    public void onAlert(String payload) {
        try {
            AlertEvent a = json.readValue(payload, AlertEvent.class);
            if (!"CRITICAL".equals(a.getLevel())) return;
            // 只对 OPEN 状态的告警建立/复用回放会话；ACK/CLOSED 不再触发
            if (a.getStatus() != null && !"OPEN".equals(a.getStatus())) return;
            createSessionForAlert(a);
        } catch (Exception e) {
            log.warn("playback alert listener err: {}", e.getMessage());
        }
    }

    public PlaybackSession createSessionForAlert(AlertEvent a) {
        OffsetDateTime center = a.getTime();
        // 幂等：同一 (bed_id, trigger_alert_id) 已有 ACTIVE 会话则直接复用，避免一次抢救事件产生 5 份重叠回放
        if (a.getId() != null) {
            Optional<PlaybackSession> exist = sessionRepo
                .findFirstByBedIdAndTriggerAlertIdAndStatus(a.getBedId(), a.getId(), "ACTIVE");
            if (exist.isPresent()) {
                log.info("playback session exists (id={}), skip duplicate for bed={} alert={}",
                    exist.get().getId(), a.getBedId(), a.getId());
                return exist.get();
            }
        }
        PlaybackSession s = new PlaybackSession();
        s.setBedId(a.getBedId());
        s.setPatientId(a.getPatientId());
        s.setTriggerAlertId(a.getId());
        s.setStartAt(center.minusMinutes(windowMin));
        s.setEndAt(center.plusMinutes(windowMin));
        s.setStatus("ACTIVE");
        s.setSummary("自动建立 - " + a.getAlertType() + " - " + a.getMessage());
        s.setCreatedAt(OffsetDateTime.now());
        sessionRepo.save(s);
        buildItems(s);
        log.info("playback session created: id={} bed={} alert={} [{} ~ {}]",
            s.getId(), s.getBedId(), a.getId(), s.getStartAt(), s.getEndAt());
        return s;
    }

    /** 手工触发：用于抢救事件未触发告警时手动建立 */
    public PlaybackSession createManual(Long bedId, Long patientId, OffsetDateTime center) {
        PlaybackSession s = new PlaybackSession();
        s.setBedId(bedId);
        s.setPatientId(patientId);
        s.setStartAt(center.minusMinutes(windowMin));
        s.setEndAt(center.plusMinutes(windowMin));
        s.setStatus("ACTIVE");
        s.setSummary("手工建立");
        s.setCreatedAt(OffsetDateTime.now());
        sessionRepo.save(s);
        buildItems(s);
        return s;
    }

    private void buildItems(PlaybackSession s) {
        // 1) 衍生指标
        try {
            var rows = tsDao.queryMetricWindow(s.getBedId(), 0L, s.getStartAt(), s.getEndAt());
            for (var r : rows) {
                PlaybackItem pi = new PlaybackItem();
                pi.setSessionId(s.getId());
                pi.setTime(((java.sql.Timestamp) r.get("time")).toInstant().atOffset(java.time.ZoneOffset.UTC));
                pi.setSourceType("VITAL");
                pi.setPayload(json.writeValueAsString(Map.of(
                    "avg", r.get("avg_value"), "min", r.get("min_value"),
                    "max", r.get("max_value"), "last", r.get("last_value"))));
                itemRepo.save(pi);
            }
        } catch (Exception ignore) {}
        // 2) 告警
        for (AlertEvent a : alertRepo.findByBedAndTimeRange(s.getBedId(), s.getStartAt(), s.getEndAt())) {
            try {
                PlaybackItem pi = new PlaybackItem();
                pi.setSessionId(s.getId());
                pi.setTime(a.getTime());
                pi.setSourceType("ALERT");
                pi.setRefId(a.getId());
                pi.setPayload(json.writeValueAsString(a));
                itemRepo.save(pi);
            } catch (Exception ignore) {}
        }
        // 3) 医嘱
        for (var o : orderRepo.findInRange(s.getBedId(), s.getStartAt(), s.getEndAt())) {
            try {
                PlaybackItem pi = new PlaybackItem();
                pi.setSessionId(s.getId());
                pi.setTime(o.getTime());
                pi.setSourceType("ORDER");
                pi.setRefId(o.getId());
                pi.setPayload(json.writeValueAsString(o));
                itemRepo.save(pi);
            } catch (Exception ignore) {}
        }
        // 4) 护理
        for (var n : nursingRepo.findInRange(s.getBedId(), s.getStartAt(), s.getEndAt())) {
            try {
                PlaybackItem pi = new PlaybackItem();
                pi.setSessionId(s.getId());
                pi.setTime(n.getTime());
                pi.setSourceType("NURSING");
                pi.setRefId(n.getId());
                pi.setPayload(json.writeValueAsString(n));
                itemRepo.save(pi);
            } catch (Exception ignore) {}
        }
    }

    public List<PlaybackItem> items(Long sessionId) { return itemRepo.findBySession(sessionId); }
    public List<PlaybackSession> byBed(Long bedId) { return sessionRepo.findByBedIdOrderByStartAtDesc(bedId); }
}
