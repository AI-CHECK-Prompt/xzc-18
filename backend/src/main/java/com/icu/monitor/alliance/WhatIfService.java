package com.icu.monitor.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icu.monitor.domain.alliance.PlanTemplate;
import com.icu.monitor.domain.alliance.SharedCase;
import com.icu.monitor.domain.alliance.WhatIfSession;
import com.icu.monitor.repository.alliance.PlanTemplateRepo;
import com.icu.monitor.repository.alliance.SharedCaseRepo;
import com.icu.monitor.repository.alliance.WhatIfSessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 事后回放（WhatIf）服务
 * <p>
 * 场景：某患者实际已转归（DECEASED/SURVIVED），现在要"如果当时采用了推荐方案，结果会如何？"
 * 模拟逻辑：
 * 1. 取该患者的真实治疗路径 + 真实转归
 * 2. 叠加推荐方案的关键动作
 * 3. 按"证据等级"调整风险系数：
 *    - 等级 A（指南）：死亡概率 -0.10
 *    - 等级 B（RCT/多案例）：死亡概率 -0.05
 *    - 等级 C（兄弟院区相似病例）：死亡概率 -0.02
 * 4. 输出新的时间轴 + 证据链
 */
@Service
public class WhatIfService {
    private static final Logger log = LoggerFactory.getLogger(WhatIfService.class);

    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private PlanTemplateRepo planTemplateRepo;
    @Autowired private WhatIfSessionRepo whatifRepo;
    @Autowired private ObjectMapper om;

    /**
     * 推演"如果当时采用推荐方案"的结果
     * @param allianceId 联盟
     * @param sharedCaseId 实际病例
     * @param planTemplateId 推荐方案
     */
    @Transactional
    public WhatIfResult simulate(long allianceId, long sharedCaseId, long planTemplateId) {
        SharedCase sc = sharedCaseRepo.findById(sharedCaseId)
            .orElseThrow(() -> new IllegalArgumentException("shared case not found: " + sharedCaseId));
        PlanTemplate plan = planTemplateRepo.findById(planTemplateId)
            .orElseThrow(() -> new IllegalArgumentException("plan not found: " + planTemplateId));

        log.info("【WhatIf】联盟 {} case={} plan={} 开始推演", allianceId, sharedCaseId, planTemplateId);

        // 1) 实际基线死亡率：基于 SOFA 经验值（演示用 SOFA -> 死亡率映射）
        double actualMortality = estimateMortality(sc);
        double baseMortality = actualMortality;

        // 2) 证据等级影响
        double riskReduction = 0;
        switch (plan.getEvidenceLevel()) {
            case "A": riskReduction = 0.10; break;
            case "B": riskReduction = 0.05; break;
            case "C": riskReduction = 0.02; break;
            default:  riskReduction = 0.01;
        }
        double predictedMortality = Math.max(0, baseMortality - riskReduction);
        String actualOutcome = sc.getOutcome();
        String predictedOutcome = predictedMortality < 0.3 ? "SURVIVED" :
                                  predictedMortality < 0.6 ? "TRANSFERRED" : "DECEASED";

        // 3) 时间轴差异：合并实际医嘱 + 推荐方案关键动作
        ArrayNode timeline = om.createArrayNode();
        if (sc.getTreatmentPath() != null && sc.getTreatmentPath().isArray()) {
            for (JsonNode step : sc.getTreatmentPath()) timeline.add(step);
        }
        if (plan.getSteps() != null && plan.getSteps().isArray()) {
            int offset = timeline.size();
            for (int i = 0; i < plan.getSteps().size(); i++) {
                JsonNode s = plan.getSteps().get(i);
                ObjectNode o = om.createObjectNode();
                o.put("time", "T+" + (offset + i) + "h");
                o.put("source", "RECOMMENDED");
                o.put("evidenceLevel", plan.getEvidenceLevel());
                if (s.has("action")) o.put("action", s.get("action").asText());
                else if (s.isTextual()) o.put("action", s.asText());
                timeline.add(o);
            }
        }

        // 4) 证据链
        ArrayNode chain = om.createArrayNode();
        ObjectNode e1 = om.createObjectNode();
        e1.put("type", "ACTUAL");
        e1.put("outcome", actualOutcome);
        e1.put("mortality", Math.round(actualMortality * 1000) / 1000.0);
        chain.add(e1);
        ObjectNode e2 = om.createObjectNode();
        e2.put("type", "RECOMMENDED");
        e2.put("evidenceLevel", plan.getEvidenceLevel());
        e2.put("basedOn", plan.getBasedOn());
        e2.put("url", plan.getSourceUrl());
        e2.put("riskReduction", riskReduction);
        chain.add(e2);
        ObjectNode e3 = om.createObjectNode();
        e3.put("type", "PREDICTED");
        e3.put("outcome", predictedOutcome);
        e3.put("mortality", Math.round(predictedMortality * 1000) / 1000.0);
        chain.add(e3);

        // 5) 持久化
        WhatIfSession s = new WhatIfSession();
        s.setAllianceId(allianceId);
        s.setSharedCaseId(sharedCaseId);
        s.setPlanTemplateId(planTemplateId);
        s.setSourcePatientId(sharedCaseId);
        s.setActualOutcome(actualOutcome);
        s.setPredictedOutcome(predictedOutcome);
        s.setMortalityDelta(predictedMortality - actualMortality);
        s.setTimelineDelta(timeline);
        s.setEvidenceChain(chain);
        s.setCreatedAt(OffsetDateTime.now());
        WhatIfSession saved = whatifRepo.save(s);

        WhatIfResult r = new WhatIfResult();
        r.sessionId = saved.getId();
        r.sharedCaseId = sharedCaseId;
        r.planTemplateId = planTemplateId;
        r.actualOutcome = actualOutcome;
        r.predictedOutcome = predictedOutcome;
        r.actualMortality = actualMortality;
        r.predictedMortality = predictedMortality;
        r.mortalityDelta = predictedMortality - actualMortality;
        r.timeline = timeline;
        r.evidenceChain = chain;
        log.info("【WhatIf】推演完成 session={} {} -> {} 死亡率变化={}",
            saved.getId(), actualOutcome, predictedOutcome, r.mortalityDelta);
        return r;
    }

    /** 简单 SOFA -> 死亡率经验映射（演示用） */
    private double estimateMortality(SharedCase sc) {
        double sofa = sc.getSofaAdmission() != null ? sc.getSofaAdmission() : 4.0;
        // SOFA 0-3: 5%, 4-5: 15%, 6-7: 30%, 8-11: 50%, 12+: 80%
        if (sofa <= 3)  return 0.05;
        if (sofa <= 5)  return 0.15;
        if (sofa <= 7)  return 0.30;
        if (sofa <= 11) return 0.50;
        return 0.80;
    }

    public static class WhatIfResult {
        public Long sessionId;
        public Long sharedCaseId;
        public Long planTemplateId;
        public String actualOutcome;
        public String predictedOutcome;
        public double actualMortality;
        public double predictedMortality;
        public double mortalityDelta;
        public JsonNode timeline;
        public JsonNode evidenceChain;
    }
}
