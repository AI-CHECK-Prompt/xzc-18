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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评分引擎：基于 Drools。
 *  - 启动时从 DB 加载所有启用规则
 *  - 医院可通过 /api/scoring/rule 接口动态新增/修改 DRL
 *  - 接收采样数据，刷新每床每患者最近 freshness 窗口内的生命体征
 *  - 滚动计算 MEWS / SOFA（粒度可配；新转入患者前 5 分钟使用加强档 5s）
 *
 * 2026-08 质控案例修复（脓毒症休克患者 GCS=8 被兜底成 15，延误早期干预）：
 *  1) GCS 通道缺失时不再默认 15，按 gcsMissing=true 上报，DRL 须按"中重度神经受损"补分
 *  2) rollScore 粒度可配（默认 10s；新转入患者前 5 分钟 5s），避免长定时器掩盖早期恶化
 *  3) recentByBed 在患者切换时整窗清零，杜绝上一位患者生命体征污染
 *  4) 真滑动窗：每通道带 lastUpdateTime，超过 freshness（默认 60s）的通道视作缺失
 *  5) 缺通道 → incompleteAssessment=true；DRL 禁止在 incomplete 时输出 NORMAL
 */
@Service
public class ScoringEngine {
    private static final Logger log = LoggerFactory.getLogger("【SCORING】");

    /** 评分要参考的关键通道集合；未在此集合中的通道不进 SOFA/MEWS */
    private static final Set<String> SCORING_CHANNELS = new HashSet<>(Arrays.asList(
        "GCS", "HR", "SBP", "MAP", "RR", "TEMP", "SPO2", "AVPU",
        "PF", "PLT", "BILIRUBIN", "DOPAMINE", "DOBUTAMINE", "CREATININE"
    ));

    private final ScoringRuleRepo ruleRepo;
    private final TimeSeriesDao tsDao;
    private final Map<String, KieContainer> containers = new ConcurrentHashMap<>();

    /**
     * 每床每患者最近有效生命体征（值 + 上报时间）。
     * key: bedId
     *  - 切换患者时整窗清空（旧 patient 的 HR/SBP/GCS 不能再算到新 patient 头上）
     */
    private final Map<Long, BedWindow> recentByBed = new ConcurrentHashMap<>();
    /** 仅记录最近一次绑定的 patientId（用于检测换患者） */
    private final Map<Long, Long> recentPatient = new ConcurrentHashMap<>();
    /** 患者绑定到床位的时刻（用于 patientJustAdmitted 判断） */
    private final Map<Long, OffsetDateTime> patientBoundAt = new ConcurrentHashMap<>();

    // ---- 可配参数 ----
    /** 通道新鲜度：超过该秒数未上报即视为缺失（避免 60s 兜底假阳性） */
    @Value("${icu.scoring.freshness-sec:60}")
    private int freshnessSec;
    /** 常规滚动评分间隔（ms） */
    @Value("${icu.scoring.roll-interval-ms:10000}")
    private long rollIntervalMs;
    /** 新转入患者"加强"窗口（秒）：此期间使用更密评分 */
    @Value("${icu.scoring.boost-window-sec:300}")
    private int boostWindowSec;
    /** 加强窗口内的滚动间隔（ms） */
    @Value("${icu.scoring.boost-interval-ms:5000}")
    private long boostIntervalMs;
    /** 评估"刚转入"的最大秒数（强提醒；与 boost-window-sec 取较小者） */
    @Value("${icu.scoring.just-admitted-sec:300}")
    private int justAdmittedSec;

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
        log.info("【SCORING】init: freshness={}s roll={}ms boost={}ms boostWindow={}s",
            freshnessSec, rollIntervalMs, boostIntervalMs, boostWindowSec);
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

    /**
     * 接收一条采样点：刷新当前患者最近值。
     * 关键：检测到 patientId 变化时整窗清零，杜绝旧患者污染。
     *
     * 注意：不再按 SCORING_CHANNELS 白名单过滤，保留所有通道以便医院自定义规则使用；
     * 真正决定"哪些通道是缺失"的是 buildContext 端的 freshness 过滤 + SCORING_CHANNELS 查表。
     */
    public void evaluate(UnifiedMessage m) {
        if (m.getPatientId() == null) return;
        if (m.getChannelCode() == null || m.getValueNum() == null) return;

        Long bedId = m.getBedId();
        Long newPid = m.getPatientId();
        Long oldPid = recentPatient.get(bedId);
        if (oldPid != null && !oldPid.equals(newPid)) {
            // 床位换患者：整窗清零，并记录新患者的绑定时刻
            recentByBed.remove(bedId);
            patientBoundAt.put(bedId, m.getTime() != null ? m.getTime() : OffsetDateTime.now(ZoneOffset.UTC));
            log.warn("【SCORING】patient-switch: bed={} oldPid={} newPid={} -> cleared recent window",
                bedId, oldPid, newPid);
        } else if (oldPid == null) {
            // 首次见到这床 / 这患者
            patientBoundAt.putIfAbsent(bedId, m.getTime() != null ? m.getTime() : OffsetDateTime.now(ZoneOffset.UTC));
        }
        recentPatient.put(bedId, newPid);

        BedWindow w = recentByBed.computeIfAbsent(bedId, k -> new BedWindow(newPid));
        w.put(m.getChannelCode(), m.getValueNum(), m.getTime() != null ? m.getTime() : OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 真·滑动窗：每通道带 lastT，rollScore 时按 freshness 过滤。
     * 缺数据或超期的通道会被记入 ctx.missingChannels，DRL 必须按"最不利"补分。
     */
    private static class BedWindow {
        final Long patientId;
        final Map<String, Double> v = new HashMap<>();
        final Map<String, OffsetDateTime> t = new HashMap<>();
        BedWindow(Long pid) { this.patientId = pid; }
        synchronized void put(String ch, double val, OffsetDateTime ts) {
            v.put(ch, val);
            t.put(ch, ts);
        }
        synchronized Double getFresh(String ch, OffsetDateTime now, int freshnessSec) {
            OffsetDateTime ts = t.get(ch);
            if (ts == null) return null;
            if (Duration.between(ts, now).getSeconds() > freshnessSec) return null;
            return v.get(ch);
        }
        synchronized OffsetDateTime lastT() {
            OffsetDateTime max = null;
            for (OffsetDateTime x : t.values()) {
                if (x == null) continue;
                if (max == null || x.isAfter(max)) max = x;
            }
            return max;
        }
    }

    /**
     * 滚动评分（可配间隔 + 新患者加强档）。
     * 同时用 fixedDelayString 兼容从 yml 注入间隔。
     * 注：rollScore 走"加强"档需要在 boost 窗口内由外层触发；这里双轨：
     *   - rollNormal 走常规间隔（roll-interval-ms）
     *   - rollBoost   走加强间隔（boost-interval-ms）
     * 两个 Scheduled 各自独立计时；为避免重复评估，加锁去重。
     */
    @Scheduled(fixedDelayString = "${icu.scoring.roll-interval-ms:10000}")
    public void rollScore() {
        rollOnce("normal");
    }
    @Scheduled(fixedDelayString = "${icu.scoring.boost-interval-ms:5000}")
    public void rollScoreBoost() {
        rollOnce("boost");
    }

    private final Object rollLock = new Object();
    private volatile long lastRollAtMs = 0L;

    private void rollOnce(String mode) {
        if (recentByBed.isEmpty()) return;
        // 加强档：跳过"无新患者在加强窗口内"的床位，避免空转
        if ("boost".equals(mode)) {
            boolean any = false;
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            for (Map.Entry<Long, OffsetDateTime> e : patientBoundAt.entrySet()) {
                if (e.getValue() != null && Duration.between(e.getValue(), now).getSeconds() <= boostWindowSec) {
                    any = true; break;
                }
            }
            if (!any) return;
        }
        long nowMs = System.currentTimeMillis();
        synchronized (rollLock) {
            // 同一时刻两个调度器先后跑会重复写库：保证两次评估至少间隔 1s
            if (nowMs - lastRollAtMs < 1000) return;
            lastRollAtMs = nowMs;
            doRoll();
        }
    }

    private void doRoll() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int n = 0;
        for (Map.Entry<Long, BedWindow> e : recentByBed.entrySet()) {
            Long bedId = e.getKey();
            BedWindow w = e.getValue();
            Long patientId = recentPatient.get(bedId);
            if (patientId == null) continue;
            OffsetDateTime bound = patientBoundAt.get(bedId);
            boolean justAdmitted = bound != null
                && Duration.between(bound, now).getSeconds() <= justAdmittedSec;

            // 构造上下文：缺通道置 null 并登记
            ScoreContext mews = buildContext("MEWS", bedId, patientId, now, w, justAdmitted);
            fire("MEWS", mews);
            ScoreContext sofa = buildContext("SOFA", bedId, patientId, now, w, justAdmitted);
            fire("SOFA", sofa);
            n++;
        }
        if (n > 0) log.debug("【SCORING】roll: beds={}", n);
    }

    private ScoreContext buildContext(String ruleCode, Long bedId, Long patientId,
                                      OffsetDateTime now, BedWindow w, boolean justAdmitted) {
        ScoreContext ctx = new ScoreContext();
        ctx.setRuleCode(ruleCode);
        ctx.setPatientId(patientId);
        ctx.setBedId(bedId);
        ctx.setTime(now);
        ctx.setPatientJustAdmitted(justAdmitted);

        List<String> missing = new ArrayList<>();
        // ---- MEWS 通道 ----
        Double hr  = w.getFresh("HR",   now, freshnessSec); if (hr  == null) missing.add("HR");
        Double sbp = w.getFresh("SBP",  now, freshnessSec); if (sbp == null) missing.add("SBP");
        Double rr  = w.getFresh("RR",   now, freshnessSec); if (rr  == null) missing.add("RR");
        Double tmp = w.getFresh("TEMP", now, freshnessSec); if (tmp == null) missing.add("TEMP");
        String avpu = null; Double avpuV = w.getFresh("AVPU", now, freshnessSec);
        if (avpuV == null) missing.add("AVPU"); else avpu = String.valueOf(avpuV.intValue());
        // ---- SOFA 通道 ----
        Double pf  = w.getFresh("PF",        now, freshnessSec); if (pf  == null) missing.add("PF");
        Double plt = w.getFresh("PLT",       now, freshnessSec); if (plt == null) missing.add("PLT");
        Double bil = w.getFresh("BILIRUBIN", now, freshnessSec); if (bil == null) missing.add("BILIRUBIN");
        Double dop = w.getFresh("DOPAMINE",  now, freshnessSec);
        Double dob = w.getFresh("DOBUTAMINE",now, freshnessSec);
        Double map = w.getFresh("MAP",       now, freshnessSec); if (map == null) missing.add("MAP");
        Double cre = w.getFresh("CREATININE",now, freshnessSec); if (cre == null) missing.add("CREATININE");
        // GCS：缺则置 null + gcsMissing=true（不再兜底 15）
        Double gcsV = w.getFresh("GCS", now, freshnessSec);
        Integer gcs = null;
        boolean gcsMissing = false;
        if (gcsV == null) {
            gcsMissing = true;
            missing.add("GCS");
        } else {
            gcs = gcsV.intValue();
        }

        ctx.setHr(hr); ctx.setSbp(sbp); ctx.setRr(rr); ctx.setTemp(tmp); ctx.setAvpu(avpu);
        ctx.setPfRatio(pf); ctx.setPlt(plt); ctx.setBilirubin(bil);
        ctx.setDopamine(dop); ctx.setDobutamine(dob); ctx.setMap(map);
        ctx.setGcs(gcs);
        ctx.setCreatinine(cre);
        ctx.setGcsMissing(gcsMissing);
        ctx.setMissingChannels(missing);
        // 任意关键通道缺失 或 患者刚转入 → 评估不完整；DRL 禁止输出 NORMAL
        ctx.setIncompleteAssessment(!missing.isEmpty() || justAdmitted);
        OffsetDateTime lastT = w.lastT();
        ctx.setSecondsSinceLastSample(lastT == null ? -1L : Duration.between(lastT, now).getSeconds());
        return ctx;
    }

    private void fire(String code, ScoreContext ctx) {
        // 始终取该规则代码的"最高版本"（向前兼容：医院加 v2 即可热生效新逻辑）
        KieContainer kc = null;
        int bestV = -1;
        String prefix = code + "_v";
        for (String n : containers.keySet()) {
            if (!n.startsWith(prefix)) continue;
            int v;
            try { v = Integer.parseInt(n.substring(prefix.length())); }
            catch (NumberFormatException e) { continue; }
            if (v > bestV) { bestV = v; kc = containers.get(n); }
        }
        if (kc == null) return;
        // KieSession 在当前 Drools 9.44 不实现 AutoCloseable；用 try/finally 显式 dispose
        KieSession session = kc.newKieSession();
        try {
            session.insert(ctx);
            session.fireAllRules();
            for (Object o : session.getObjects()) {
                if (o instanceof ScoreResult) {
                    ScoreResult sr = (ScoreResult) o;
                    persist(sr, ctx);
                    log.info("score: bed={} pat={} rule={} score={} level={} incomplete={} missing={} justAdmitted={} gcsMissing={}",
                        ctx.getBedId(), ctx.getPatientId(), code,
                        sr.getScore(), sr.getLevel(),
                        ctx.isIncompleteAssessment(), ctx.getMissingChannels(),
                        ctx.isPatientJustAdmitted(), ctx.isGcsMissing());
                }
            }
        } catch (Exception ex) {
            log.warn("fire rule err: {}", ex.getMessage());
        } finally {
            try { session.dispose(); } catch (Exception ignore) {}
        }
    }

    private void persist(ScoreResult sr, ScoreContext ctx) {
        try {
            // 把"评估完整性 + 缺通道 + GCS 兜底事件"写进 detail JSONB，便于事后追溯
            String detail = String.format(
                "{\"hr\":%s,\"sbp\":%s,\"rr\":%s,\"temp\":%s,\"map\":%s,\"gcs\":%s," +
                "\"gcsMissing\":%s,\"missingChannels\":%s,\"incompleteAssessment\":%s," +
                "\"justAdmitted\":%s,\"secondsSinceLastSample\":%d}",
                n(ctx.getHr()), n(ctx.getSbp()), n(ctx.getRr()), n(ctx.getTemp()), n(ctx.getMap()),
                ctx.getGcs() == null ? "null" : ctx.getGcs().toString(),
                ctx.isGcsMissing(),
                jsonArr(ctx.getMissingChannels()),
                ctx.isIncompleteAssessment(),
                ctx.isPatientJustAdmitted(),
                ctx.getSecondsSinceLastSample()
            );
            tsDao.insertScoringResult(ctx.getTime(), ctx.getPatientId(), ctx.getBedId(),
                sr.getRuleCode(), sr.getScore(), sr.getLevel(), detail);
        } catch (Exception ignore) {}
    }

    private static String n(Double v) { return v == null ? "null" : v.toString(); }
    private static String jsonArr(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(xs.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }
}
