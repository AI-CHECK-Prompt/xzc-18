package com.icu.monitor.controller.alliance;

import com.icu.monitor.alliance.AllianceSyncService;
import com.icu.monitor.alliance.JointQcService;
import com.icu.monitor.alliance.PlanRecommendService;
import com.icu.monitor.alliance.SimilarCaseService;
import com.icu.monitor.alliance.WhatIfService;
import com.icu.monitor.domain.alliance.*;
import com.icu.monitor.repository.HospitalRepo;
import com.icu.monitor.repository.alliance.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 联合质控 REST API
 * 端点前缀：/api/alliance
 */
@RestController
@RequestMapping("/api/alliance")
@CrossOrigin(origins = "*")
public class AllianceController {

    @Autowired private HospitalAllianceRepo allianceRepo;
    @Autowired private AllianceMemberRepo memberRepo;
    @Autowired private HospitalRepo hospitalRepo;
    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private GuidelineRepo guidelineRepo;
    @Autowired private PlanTemplateRepo planTemplateRepo;
    @Autowired private QcMetricRepo qcMetricRepo;
    @Autowired private JointReportRepo jointReportRepo;
    @Autowired private WhatIfSessionRepo whatifRepo;
    @Autowired private AllianceSyncService syncService;
    @Autowired private SimilarCaseService similarService;
    @Autowired private PlanRecommendService recommendService;
    @Autowired private JointQcService qcService;
    @Autowired private WhatIfService whatIfService;
    @Autowired private ObjectMapper om;

    // -------- 1. 联盟管理 --------

    @GetMapping("/list")
    public List<HospitalAlliance> list() { return allianceRepo.findAll(); }

    @PostMapping("/create")
    public HospitalAlliance create(@RequestBody HospitalAlliance a) {
        a.setCreatedAt(OffsetDateTime.now());
        return allianceRepo.save(a);
    }

    @GetMapping("/{id}/members")
    public List<Map<String, Object>> members(@PathVariable Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AllianceMember m : memberRepo.findByAllianceId(id)) {
            Map<String, Object> o = new HashMap<>();
            o.put("memberId", m.getId());
            o.put("hospitalId", m.getHospitalId());
            o.put("role", m.getRole());
            o.put("joinedAt", m.getJoinedAt());
            hospitalRepo.findById(m.getHospitalId()).ifPresent(h -> {
                o.put("code", h.getCode());
                o.put("name", h.getName());
            });
            out.add(o);
        }
        return out;
    }

    @PostMapping("/{id}/join")
    public AllianceMember join(@PathVariable Long id, @RequestParam Long hospitalId,
                               @RequestParam(defaultValue = "MEMBER") String role) {
        AllianceMember m = new AllianceMember();
        m.setAllianceId(id);
        m.setHospitalId(hospitalId);
        m.setRole(role);
        m.setJoinedAt(OffsetDateTime.now());
        return memberRepo.save(m);
    }

    // -------- 2. 脱敏共享池 --------

    /** 把本院某患者脱敏后写入共享池 */
    @PostMapping("/share")
    public Map<String, Object> share(@RequestParam long allianceId,
                                    @RequestParam long hospitalId,
                                    @RequestParam long patientId,
                                    @RequestParam String drgCode,
                                    @RequestParam(required = false) String mdcCode,
                                    @RequestParam(required = false) Double sofaAdmission,
                                    @RequestBody(required = false) Map<String, Object> body) {
        JsonNode vitals = body != null && body.get("vitals") != null ? om.valueToTree(body.get("vitals")) : null;
        JsonNode lab    = body != null && body.get("lab")    != null ? om.valueToTree(body.get("lab"))    : null;
        JsonNode path   = body != null && body.get("path")   != null ? om.valueToTree(body.get("path"))   : null;
        JsonNode rescue = body != null && body.get("rescue") != null ? om.valueToTree(body.get("rescue")) : null;
        String outcome  = body != null ? (String) body.getOrDefault("outcome", "SURVIVED") : "SURVIVED";
        Integer losDays = body != null ? ((Number) body.getOrDefault("losDays", 7)).intValue() : 7;
        Boolean infect  = body != null && Boolean.TRUE.equals(body.get("infection"));

        SharedCase sc = syncService.share(allianceId, hospitalId, patientId, drgCode, mdcCode,
            sofaAdmission, vitals, lab, path, rescue, outcome, losDays, infect, OffsetDateTime.now());

        return Map.of("ok", true, "sharedCaseId", sc.getId(), "poolKey", sc.getPoolPatientKey());
    }

    /** 共享池查询（按 DRG/年龄段） */
    @GetMapping("/pool")
    public List<Map<String, Object>> pool(@RequestParam long allianceId,
                                          @RequestParam(required = false) String drgCode,
                                          @RequestParam(required = false) String ageBand,
                                          @RequestParam(defaultValue = "100") int limit) {
        List<SharedCase> all = sharedCaseRepo.findAll();
        return all.stream()
            .filter(s -> s.getAllianceId().equals(allianceId))
            .filter(s -> drgCode == null || drgCode.equals(s.getDrgCode()))
            .filter(s -> ageBand == null || ageBand.equals(s.getAgeBand()))
            .sorted(Comparator.comparing(SharedCase::getAdmissionAt).reversed())
            .limit(limit)
            .map(this::toPoolView)
            .toList();
    }

    @GetMapping("/pool/{id}")
    public Map<String, Object> poolDetail(@PathVariable Long id) {
        SharedCase s = sharedCaseRepo.findById(id).orElseThrow();
        return toPoolView(s);
    }

    private Map<String, Object> toPoolView(SharedCase s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("sourceHospital", s.getSourceHospital());
        m.put("drgCode", s.getDrgCode());
        m.put("mdcCode", s.getMdcCode());
        m.put("ageBand", s.getAgeBand());
        m.put("gender", s.getGender());
        m.put("sofaAdmission", s.getSofaAdmission());
        m.put("diagnosisText", s.getDiagnosisText());
        m.put("outcome", s.getOutcome());
        m.put("losDays", s.getLosDays());
        m.put("infection", s.getInfectionFlag());
        m.put("admissionAt", s.getAdmissionAt());
        m.put("sharedAt", s.getSharedAt());
        m.put("vitals", s.getVitalsSummary());
        m.put("lab", s.getLabSummary());
        m.put("path", s.getTreatmentPath());
        m.put("rescue", s.getRescueEvents());
        return m;
    }

    // -------- 3. 相似病例检索（Top-10） --------

    /**
     * 新患者入院 24 小时内调用
     * 入参：allianceId, drgCode, 16 维生命体征+化验特征
     */
    @PostMapping("/similar/top")
    public Map<String, Object> topSimilar(@RequestParam long allianceId,
                                          @RequestParam String drgCode,
                                          @RequestParam(required = false) Long currentHospitalId,
                                          @RequestParam(defaultValue = "10") int topN,
                                          @RequestBody Map<String, Object> features) {
        double[] v = extractVector(features);
        long t0 = System.currentTimeMillis();
        List<SimilarCaseService.SimilarHit> hits = similarService.searchTopN(
            allianceId, v, drgCode, topN, currentHospitalId);
        List<Map<String, Object>> result = hits.stream().map(h -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sharedCaseId", h.sharedCase.getId());
            m.put("sourceHospital", h.sharedCase.getSourceHospital());
            m.put("drgCode", h.sharedCase.getDrgCode());
            m.put("ageBand", h.sharedCase.getAgeBand());
            m.put("gender", h.sharedCase.getGender());
            m.put("similarity", Math.round(h.similarity * 1000) / 1000.0);
            m.put("outcome", h.sharedCase.getOutcome());
            m.put("losDays", h.sharedCase.getLosDays());
            m.put("infection", h.sharedCase.getInfectionFlag());
            m.put("admissionAt", h.sharedCase.getAdmissionAt());
            m.put("treatmentPath", h.sharedCase.getTreatmentPath());
            m.put("rescueEvents", h.sharedCase.getRescueEvents());
            return m;
        }).toList();
        return Map.of(
            "ok", true,
            "allianceId", allianceId,
            "drgCode", drgCode,
            "topN", topN,
            "cost", System.currentTimeMillis() - t0,
            "hits", result
        );
    }

    // -------- 4. 治疗方案推荐（带证据级别） --------

    @PostMapping("/plan/recommend")
    public PlanRecommendService.RecommendResult recommend(
            @RequestParam long allianceId,
            @RequestParam String drgCode,
            @RequestParam(required = false) Long currentHospitalId,
            @RequestBody Map<String, Object> features) {
        double[] v = extractVector(features);
        return recommendService.recommend(allianceId, drgCode, v, currentHospitalId);
    }

    @GetMapping("/guideline")
    public List<Guideline> guidelineList(@RequestParam(required = false) String drgCode) {
        return drgCode == null ? guidelineRepo.findAll() : guidelineRepo.findByDrgCode(drgCode);
    }

    @GetMapping("/plan/template")
    public List<PlanTemplate> planTemplates(@RequestParam long allianceId,
                                            @RequestParam(required = false) String drgCode) {
        if (drgCode != null)
            return planTemplateRepo.findByAllianceIdAndDrgCodeOrderByEvidenceLevelAsc(allianceId, drgCode);
        return planTemplateRepo.findByAllianceId(allianceId);
    }

    // -------- 5. 联合质控对比 --------

    @GetMapping("/qc/compare")
    public Map<String, Object> qcCompare(@RequestParam long allianceId,
                                         @RequestParam String drgCode,
                                         @RequestParam(required = false) String quarter) {
        String q = quarter != null ? quarter : qcService.currentQuarter();
        return qcService.compareByDrg(allianceId, drgCode, q);
    }

    @GetMapping("/qc/breakdown")
    public List<Map<String, Object>> qcBreakdown(@RequestParam long allianceId,
                                                 @RequestParam(required = false) String quarter) {
        return qcService.drgBreakdown(allianceId, quarter != null ? quarter : qcService.currentQuarter());
    }

    /** 触发聚合（演示用：把共享池数据按 DRG 聚合成质控指标） */
    @PostMapping("/qc/aggregate")
    public Map<String, Object> aggregate(@RequestParam long allianceId,
                                         @RequestParam String drgCode,
                                         @RequestParam(required = false) String quarter) {
        String q = quarter != null ? quarter : qcService.currentQuarter();
        List<QcMetric> res = qcService.aggregateByDrg(allianceId, drgCode, q);
        return Map.of("ok", true, "count", res.size(), "metrics", res);
    }

    /** SOFA 变化曲线（按 DRG + 院区） */
    @GetMapping("/qc/sofa-curve")
    public List<Map<String, Object>> sofaCurve(@RequestParam long allianceId,
                                               @RequestParam String drgCode,
                                               @RequestParam(required = false) String quarter) {
        return qcService.aggregateByDrg(allianceId, drgCode, quarter != null ? quarter : qcService.currentQuarter())
            .stream().map(m -> {
                Map<String, Object> r = new HashMap<>();
                r.put("hospitalId", m.getHospitalId());
                r.put("sofaCurve", m.getAvgSofaCurve());
                return r;
            }).toList();
    }

    /** 生成季度报告 */
    @PostMapping("/qc/report")
    public Map<String, Object> generateReport(@RequestParam long allianceId,
                                              @RequestParam(required = false) String quarter) {
        String q = quarter != null ? quarter : qcService.currentQuarter();
        JointReport rep = qcService.generateReport(allianceId, q);
        return Map.of("ok", rep != null, "reportId", rep != null ? rep.getId() : -1, "quarter", q);
    }

    @GetMapping("/qc/report")
    public List<JointReport> listReports(@RequestParam long allianceId) {
        return jointReportRepo.findByAllianceIdOrderByGeneratedAtDesc(allianceId);
    }

    @GetMapping("/qc/report/{id}")
    public JointReport getReport(@PathVariable Long id) {
        return jointReportRepo.findById(id).orElse(null);
    }

    // -------- 6. WhatIf 事后回放 --------

    @PostMapping("/whatif/simulate")
    public WhatIfService.WhatIfResult whatIf(@RequestParam long allianceId,
                                             @RequestParam long sharedCaseId,
                                             @RequestParam long planTemplateId) {
        return whatIfService.simulate(allianceId, sharedCaseId, planTemplateId);
    }

    @GetMapping("/whatif/list")
    public List<WhatIfSession> whatIfList(@RequestParam long allianceId) {
        return whatifRepo.findByAllianceIdOrderByCreatedAtDesc(allianceId);
    }

    // -------- 工具：从 features 提取 16 维向量 --------

    private double[] extractVector(Map<String, Object> f) {
        double[] v = new double[16];
        v[0]  = num(f, "hrAvg", 80);
        v[1]  = num(f, "hrStd", 10);
        v[2]  = num(f, "sbpAvg", 120);
        v[3]  = num(f, "sbpStd", 12);
        v[4]  = num(f, "spo2Avg", 96);
        v[5]  = num(f, "spo2Std", 2);
        v[6]  = num(f, "tempAvg", 36.8);
        v[7]  = num(f, "respAvg", 18);
        v[8]  = num(f, "creatinine", 1.0);
        v[9]  = num(f, "platelet", 200);
        v[10] = num(f, "bilirubin", 0.8);
        v[11] = num(f, "dopamine", 0);
        v[12] = num(f, "lactate", 1.5);
        v[13] = num(f, "wbc", 8.0);
        v[14] = num(f, "pfRatio", 350);
        v[15] = num(f, "sofa", 4.0);
        return v;
    }

    private static double num(Map<String, Object> m, String k, double def) {
        if (m == null || m.get(k) == null) return def;
        try { return ((Number) m.get(k)).doubleValue(); } catch (Exception e) { return def; }
    }
}
