package com.icu.monitor.scoring;

import com.icu.monitor.domain.ScoringRule;
import com.icu.monitor.protocol.UnifiedMessage;
import com.icu.monitor.repository.ScoringRuleRepo;
import com.icu.monitor.repository.TimeSeriesDao;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评分引擎：基于 Drools。
 *  - 启动时从 DB 加载所有启用规则
 *  - 医院可通过 /api/scoring/rule 接口动态新增/修改 DRL
 *  - 接收采样数据，刷新每床每患者最近 60s 内的生命体征
 *  - 每分钟滚动计算一次 MEWS / SOFA
 */
@Service
public class ScoringEngine {
    private static final Logger log = LoggerFactory.getLogger("【SCORING】");

    private final ScoringRuleRepo ruleRepo;
    private final TimeSeriesDao tsDao;
    private final Map<String, KieContainer> containers = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Double>> recentByBed = new ConcurrentHashMap<>();
    private final Map<Long, Long> recentPatient = new ConcurrentHashMap<>();

    public ScoringEngine(ScoringRuleRepo ruleRepo, TimeSeriesDao tsDao) {
        this.ruleRepo = ruleRepo;
        this.tsDao = tsDao;
    }

    /** 启动加载一次；后续动态规则变更会调用 reload */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void loadAll() {
        for (ScoringRule r : ruleRepo.findByHospitalIdAndEnabledTrue(1L)) {
            try { reload(r); } catch (Exception e) { log.error("load rule {} err: {}", r.getCode(), e.getMessage()); }
        }
    }

    public synchronized void reload(ScoringRule r) {
        try {
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();
            String name = r.getCode() + "_v" + r.getVersion();
            kfs.write("src/main/resources/rules/" + name + ".drl", r.getDrlContent());
            KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
            Results res = kb.getResults();
            if (res.hasMessages(Message.Level.ERROR)) {
                log.error("DRL compile error for {}: {}", name, res.getMessages());
                return;
            }
            KieContainer kc = ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
            containers.put(name, kc);
            log.info("scoring rule loaded: {} v{}", r.getCode(), r.getVersion());
        } catch (Exception e) {
            log.error("reload rule err: {}", e.getMessage());
        }
    }

    public void evaluate(UnifiedMessage m) {
        if (m.getPatientId() == null) return;
        recentPatient.put(m.getBedId(), m.getPatientId());
        Map<String, Double> rec = recentByBed.computeIfAbsent(m.getBedId(), k -> new HashMap<>());
        rec.put(m.getChannelCode(), m.getValueNum());
    }

    /** 每 60 秒滚动评分 */
    @Scheduled(fixedDelay = 60000)
    public void rollScore() {
        if (recentByBed.isEmpty()) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<Long, Map<String, Double>> e : recentByBed.entrySet()) {
            Long bedId = e.getKey();
            Map<String, Double> v = e.getValue();
            Long patientId = recentPatient.get(bedId);
            if (patientId == null) continue;
            // MEWS
            fire("MEWS", bedId, patientId, now, v);
            // SOFA
            fire("SOFA", bedId, patientId, now, v);
        }
    }

    private void fire(String code, Long bedId, Long patientId, OffsetDateTime now, Map<String, Double> v) {
        // 简化：取任意版本
        KieContainer kc = null;
        for (String n : containers.keySet()) {
            if (n.startsWith(code + "_v")) { kc = containers.get(n); break; }
        }
        if (kc == null) return;
        ScoreContext ctx = new ScoreContext();
        ctx.setRuleCode(code);
        ctx.setPatientId(patientId);
        ctx.setBedId(bedId);
        ctx.setTime(now);
        ctx.setSbp(v.get("SBP"));
        ctx.setHr(v.get("HR"));
        ctx.setRr(v.get("RR"));
        ctx.setTemp(v.get("TEMP"));
        ctx.setMap(v.get("MAP"));
        ctx.setGcs(v.get("GCS") == null ? 15 : v.get("GCS").intValue());
        try (KieSession session = kc.newKieSession()) {
            session.insert(ctx);
            session.fireAllRules();
            for (Object o : session.getObjects()) {
                if (o instanceof ScoreResult) {
                    ScoreResult sr = (ScoreResult) o;
                    persist(sr, ctx);
                    log.info("score: bed={} rule={} score={} level={}", bedId, code, sr.getScore(), sr.getLevel());
                }
            }
        } catch (Exception ex) {
            log.warn("fire rule err: {}", ex.getMessage());
        }
    }

    private void persist(ScoreResult sr, ScoreContext ctx) {
        try {
            tsDao.insertScoringResult(ctx.getTime(), ctx.getPatientId(), ctx.getBedId(),
                sr.getRuleCode(), sr.getScore(), sr.getLevel(),
                String.format("{\"hr\":%s,\"sbp\":%s,\"rr\":%s,\"temp\":%s}",
                    ctx.getHr(), ctx.getSbp(), ctx.getRr(), ctx.getTemp()));
        } catch (Exception ignore) {}
    }
}
