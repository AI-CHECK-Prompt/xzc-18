package com.icu.monitor.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icu.monitor.domain.Patient;
import com.icu.monitor.domain.alliance.SharedCase;
import com.icu.monitor.domain.alliance.SimilarIndex;
import com.icu.monitor.repository.PatientRepo;
import com.icu.monitor.repository.alliance.SharedCaseRepo;
import com.icu.monitor.repository.alliance.SimilarIndexRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 院间共享池同步器
 * <p>
 * 把本院已出院的患者脱敏后写入联盟共享池，并构建相似病例索引。
 * 演示用：支持按 patientId 手动触发；生产可用定时任务扫描出院患者。
 */
@Service
public class AllianceSyncService {
    private static final Logger log = LoggerFactory.getLogger(AllianceSyncService.class);

    @Autowired private DeidService deidService;
    @Autowired private PatientRepo patientRepo;
    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private SimilarIndexRepo similarIndexRepo;
    @Autowired private SimilarCaseService similarCaseService;
    @Autowired private ObjectMapper om;

    /**
     * 把本院某患者脱敏后写入联盟共享池
     * @param allianceId 联盟 id
     * @param hospitalId 来源医院 id
     * @param patientId 本地患者 id
     * @param drgCode DRG 编码
     * @param mdcCode MDC 编码
     * @param sofaAdmission 入院 24h SOFA
     * @param sofaDailyCurve 真实每日 SOFA（Day 0..7，可空；空时仅写 sofa_admission 作为 Day0）
     * @param vitalsSummary 生命体征统计（min/avg/max）JSON
     * @param labSummary 化验摘要 JSON
     * @param treatmentPath 治疗路径（医嘱时序）JSON
     * @param rescueEvents 抢救事件 JSON
     * @param outcome 转归
     * @param losDays 住院天数
     * @param infectionFlag 是否感染
     * @param admissionAt 入院时间
     */
    @Transactional
    public SharedCase share(long allianceId, long hospitalId, long patientId,
                            String drgCode, String mdcCode, Double sofaAdmission,
                            JsonNode sofaDailyCurve,
                            JsonNode vitalsSummary, JsonNode labSummary,
                            JsonNode treatmentPath, JsonNode rescueEvents,
                            String outcome, Integer losDays, Boolean infectionFlag,
                            OffsetDateTime admissionAt) {
        Patient p = patientRepo.findById(patientId).orElse(null);
        if (p == null) throw new IllegalArgumentException("patient not found: " + patientId);

        // 1) 脱敏生成跨院区唯一 key
        String poolKey = deidService.poolKey(allianceId, p.getMrn());

        // 2) 查重：同 (联盟, 来源医院, poolKey) 唯一
        Optional<SharedCase> exist = sharedCaseRepo.findAll().stream()
            .filter(s -> s.getAllianceId().equals(allianceId)
                && s.getSourceHospital().equals(hospitalId)
                && s.getPoolPatientKey().equals(poolKey))
            .findFirst();
        if (exist.isPresent()) {
            log.info("【共享池】患者已存在 alliance={} hospital={} key={}", allianceId, hospitalId, poolKey);
            return exist.get();
        }

        SharedCase sc = new SharedCase();
        sc.setAllianceId(allianceId);
        sc.setSourceHospital(hospitalId);
        sc.setPoolPatientKey(poolKey);
        sc.setDrgCode(drgCode);
        sc.setMdcCode(mdcCode);
        sc.setAgeBand(p.getBirthDate() != null
            ? deidService.ageBand(p.getBirthDate(), LocalDate.now())
            : "UNKNOWN");
        sc.setGender(p.getGender());
        sc.setSofaAdmission(sofaAdmission);
        sc.setSofaDailyCurve(sofaDailyCurve);
        sc.setDiagnosisText(p.getDiagnosis());
        sc.setVitalsSummary(vitalsSummary);
        sc.setLabSummary(labSummary);
        sc.setTreatmentPath(treatmentPath);
        sc.setRescueEvents(rescueEvents);
        sc.setOutcome(outcome);
        sc.setLosDays(losDays);
        sc.setInfectionFlag(Boolean.TRUE.equals(infectionFlag));
        sc.setAdmissionAt(admissionAt != null ? admissionAt : OffsetDateTime.now());
        sc.setSharedAt(OffsetDateTime.now());
        SharedCase saved = sharedCaseRepo.save(sc);

        // 3) 构建相似病例索引
        rebuildIndex(saved);
        log.info("【共享池】新增脱敏病例 alliance={} drg={} key={}", allianceId, drgCode, poolKey);
        return saved;
    }

    /** 重新计算相似病例索引（16 维特征向量） */
    @Transactional
    public SimilarIndex rebuildIndex(SharedCase sc) {
        double[] vec = similarCaseService.buildVector(sc);
        double norm = 0.0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);

        // 查重更新
        SimilarIndex idx = similarIndexRepo.findAll().stream()
            .filter(i -> i.getSharedCaseId().equals(sc.getId()))
            .findFirst().orElse(new SimilarIndex());

        idx.setSharedCaseId(sc.getId());
        ArrayNode arr = om.createArrayNode();
        for (double v : vec) arr.add(v);
        idx.setFeatureVector(arr);
        idx.setNorm(norm);
        idx.setBuiltAt(OffsetDateTime.now());
        return similarIndexRepo.save(idx);
    }

    /** 定期重建所有索引（演示用，每天凌晨 2 点） */
    @Scheduled(cron = "0 0 2 * * *")
    public void rebuildAllDaily() {
        log.info("【共享池】每日索引重建开始");
        for (SharedCase sc : sharedCaseRepo.findAll()) {
            try { rebuildIndex(sc); } catch (Exception e) {
                log.warn("【共享池】索引重建失败 case={} : {}", sc.getId(), e.getMessage());
            }
        }
        log.info("【共享池】每日索引重建完成，共 {} 例", sharedCaseRepo.count());
    }
}
