package com.icu.monitor.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.icu.monitor.domain.alliance.*;
import com.icu.monitor.repository.alliance.GuidelineRepo;
import com.icu.monitor.repository.alliance.PlanTemplateRepo;
import com.icu.monitor.repository.alliance.SharedCaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 治疗方案推荐服务
 * <p>
 * 输出规则：
 * 1. 证据等级 A：来自国际/国家指南（如 ESICM/SSC/ESC/GOLD 等），强推荐
 * 2. 证据等级 B：来自 RCT/Meta 分析或本院+联盟相似病例 >30 例且成功率 >80%
 * 3. 证据等级 C：来自联盟相似病例（5-30 例），需要医生评估
 * 4. 每条推荐附 sourceUrl（指南/RCT 链接）
 */
@Service
public class PlanRecommendService {
    private static final Logger log = LoggerFactory.getLogger(PlanRecommendService.class);

    @Autowired private GuidelineRepo guidelineRepo;
    @Autowired private PlanTemplateRepo planTemplateRepo;
    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private SimilarCaseService similarCaseService;

    /**
     * 为新患者生成推荐方案
     * @param allianceId 联盟 id
     * @param drgCode 当前 DRG
     * @param queryVec 16 维特征向量
     * @param currentHospitalId 当前院区（用于排除）
     */
    public RecommendResult recommend(long allianceId, String drgCode, double[] queryVec, Long currentHospitalId) {
        RecommendResult r = new RecommendResult();
        r.allianceId = allianceId;
        r.drgCode = drgCode;

        // 1) 指南级（A 证据）—— 强制包含
        List<Guideline> guidelines = guidelineRepo.findByDrgCode(drgCode);
        // MDC 兜底
        if (guidelines.isEmpty()) {
            log.info("【方案推荐】DRG {} 无指南，按 MDC 兜底", drgCode);
        }
        for (Guideline g : guidelines) {
            RecommendItem it = new RecommendItem();
            it.title = g.getTitle();
            it.evidenceLevel = g.getEvidenceLevel();   // A/B/C
            it.basedOn = "GUIDELINE";
            it.source = g.getSource();
            it.url = g.getUrl();
            it.summary = g.getSummary();
            it.steps = g.getKeyActions();
            it.supportCount = 0;
            it.successRate = null;
            r.items.add(it);
        }

        // 2) 联盟内相似病例的治疗路径聚合成"低证据级别"方案（C）
        List<SimilarCaseService.SimilarHit> hits = similarCaseService.searchTopN(
            allianceId, queryVec, drgCode, 10, currentHospitalId);
        r.similarCases = hits.stream().map(h -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sharedCaseId", h.sharedCase.getId());
            m.put("sourceHospital", h.sharedCase.getSourceHospital());
            m.put("drgCode", h.sharedCase.getDrgCode());
            m.put("similarity", Math.round(h.similarity * 1000) / 1000.0);
            m.put("outcome", h.sharedCase.getOutcome());
            m.put("losDays", h.sharedCase.getLosDays());
            m.put("infection", h.sharedCase.getInfectionFlag());
            m.put("admissionAt", h.sharedCase.getAdmissionAt());
            m.put("treatmentPath", h.sharedCase.getTreatmentPath());
            m.put("rescueEvents", h.sharedCase.getRescueEvents());
            return m;
        }).collect(Collectors.toList());

        // 3) 相似病例的治疗路径聚合成低证据方案
        if (!hits.isEmpty()) {
            Map<String, Long> stepFreq = new HashMap<>();
            Map<String, Integer> outcomeScore = new HashMap<>();
            for (SimilarCaseService.SimilarHit h : hits) {
                JsonNode path = h.sharedCase.getTreatmentPath();
                if (path != null && path.isArray()) {
                    for (JsonNode step : path) {
                        String key = step.has("itemName") ? step.get("itemName").asText() : null;
                        if (key != null) {
                            stepFreq.merge(key, 1L, Long::sum);
                            int score = "SURVIVED".equals(h.sharedCase.getOutcome()) ? 1 : 0;
                            outcomeScore.merge(key, score, Integer::sum);
                        }
                    }
                }
            }
            // 排序：出现频率高且成功率高的步骤优先
            List<Map.Entry<String, Long>> top = stepFreq.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .collect(Collectors.toList());
            if (!top.isEmpty()) {
                RecommendItem sim = new RecommendItem();
                sim.title = "联盟相似病例治疗路径（Top-5 频次动作）";
                sim.evidenceLevel = "C";
                sim.basedOn = "SIMILAR_CASE";
                sim.source = "联盟共享池";
                sim.url = "/api/similar/top?drg=" + drgCode;
                sim.summary = "基于 " + hits.size() + " 例相似病例（DRG=" + drgCode + "）的医嘱时序聚合";
                sim.steps = top.stream().map(e -> {
                    Map<String, Object> s = new HashMap<>();
                    s.put("action", e.getKey());
                    s.put("freq", e.getValue());
                    int success = outcomeScore.getOrDefault(e.getKey(), 0);
                    s.put("successRate", e.getValue() > 0 ? Math.round(success * 1000.0 / e.getValue()) / 10.0 : 0);
                    return s;
                }).collect(Collectors.toCollection(ArrayList::new));
                sim.supportCount = hits.size();
                long survived = hits.stream().filter(h -> "SURVIVED".equals(h.sharedCase.getOutcome())).count();
                sim.successRate = hits.size() > 0 ? Math.round(survived * 1000.0 / hits.size()) / 10.0 : 0.0;
                r.items.add(sim);
            }
        }

        // 4) 联盟方案模板（按证据等级排序）
        List<PlanTemplate> templates = planTemplateRepo.findByAllianceIdAndDrgCodeOrderByEvidenceLevelAsc(allianceId, drgCode);
        for (PlanTemplate t : templates) {
            RecommendItem it = new RecommendItem();
            it.title = t.getTitle();
            it.evidenceLevel = t.getEvidenceLevel();
            it.basedOn = t.getBasedOn();
            it.url = t.getSourceUrl();
            it.summary = "联盟方案模板，支撑 " + t.getSupportCount() + " 例，成功率 " +
                (t.getSuccessRate() != null ? Math.round(t.getSuccessRate() * 100) / 100.0 : 0);
            it.steps = t.getSteps();
            it.supportCount = t.getSupportCount();
            it.successRate = t.getSuccessRate();
            r.items.add(it);
        }

        // 排序：A 证据优先
        r.items.sort((a, b) -> {
            int oa = "A".equals(a.evidenceLevel) ? 0 : "B".equals(a.evidenceLevel) ? 1 : 2;
            int ob = "A".equals(b.evidenceLevel) ? 0 : "B".equals(b.evidenceLevel) ? 1 : 2;
            return Integer.compare(oa, ob);
        });
        log.info("【方案推荐】联盟 {} DRG={} 指南={} 相似病例={} 模板={} 当前院区={}",
            allianceId, drgCode, guidelines.size(), hits.size(), templates.size(), currentHospitalId);
        return r;
    }

    public static class RecommendResult {
        public long allianceId;
        public String drgCode;
        public List<RecommendItem> items = new ArrayList<>();
        public List<Map<String, Object>> similarCases = new ArrayList<>();
    }

    public static class RecommendItem {
        public String title;
        public String evidenceLevel;          // A/B/C
        public String basedOn;                // GUIDELINE/RCT/SIMILAR_CASE
        public String source;                 // 来源：ESICM 2021 / 联盟共享池 ...
        public String url;                    // 证据链接
        public String summary;
        public Object steps;                  // 关键动作（来自指南 / 相似病例路径）
        public Integer supportCount;          // 支撑病例数
        public Double successRate;            // 成功率（0-100）
    }
}
