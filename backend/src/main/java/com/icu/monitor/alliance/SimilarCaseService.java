package com.icu.monitor.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.icu.monitor.domain.alliance.SharedCase;
import com.icu.monitor.domain.alliance.SimilarIndex;
import com.icu.monitor.repository.alliance.SharedCaseRepo;
import com.icu.monitor.repository.alliance.SimilarIndexRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 相似病例检索
 * <p>
 * 16 维特征向量：
 * [0] hr_avg        心率均值
 * [1] hr_std        心率标准差
 * [2] sbp_avg       收缩压均值
 * [3] sbp_std       收缩压标准差
 * [4] spo2_avg      血氧均值
 * [5] spo2_std      血氧标准差
 * [6] temp_avg      体温均值
 * [7] resp_avg      呼吸均值
 * [8] cr_avg        肌酐均值
 * [9] plt_avg       血小板均值
 * [10] bili_avg     胆红素均值
 * [11] dopa         多巴胺剂量（ug/kg/min）
 * [12] lactate      乳酸
 * [13] wbc          白细胞
 * [14] pf_ratio     氧合指数
 * [15] sofa         SOFA 评分
 * <p>
 * 余弦相似度 = dot(a,b) / (|a|*|b|)
 */
@Service
public class SimilarCaseService {
    private static final Logger log = LoggerFactory.getLogger(SimilarCaseService.class);

    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private SimilarIndexRepo similarIndexRepo;

    /** 从 SharedCase 提取 16 维向量；缺失字段使用中性默认值（与新患者查询兼容） */
    public double[] buildVector(SharedCase sc) {
        double[] v = new double[16];
        Arrays.fill(v, 0.0);

        if (sc == null) return v;

        // 从 vitalsSummary 中提取
        JsonNode vitals = sc.getVitalsSummary();
        if (vitals != null) {
            v[0] = getJsonNumber(vitals, "hr_avg", 80);
            v[1] = getJsonNumber(vitals, "hr_std", 10);
            v[2] = getJsonNumber(vitals, "sbp_avg", 120);
            v[3] = getJsonNumber(vitals, "sbp_std", 12);
            v[4] = getJsonNumber(vitals, "spo2_avg", 96);
            v[5] = getJsonNumber(vitals, "spo2_std", 2);
            v[6] = getJsonNumber(vitals, "temp_avg", 36.8);
            v[7] = getJsonNumber(vitals, "resp_avg", 18);
        } else {
            v[0] = 80; v[1] = 10; v[2] = 120; v[3] = 12;
            v[4] = 96; v[5] = 2;  v[6] = 36.8; v[7] = 18;
        }

        // 化验
        JsonNode lab = sc.getLabSummary();
        if (lab != null) {
            v[8]  = getJsonNumber(lab, "creatinine", 1.0);   // 肌酐 mg/dL
            v[9]  = getJsonNumber(lab, "platelet", 200);     // 血小板 10^9/L
            v[10] = getJsonNumber(lab, "bilirubin", 0.8);    // 胆红素 mg/dL
            v[11] = getJsonNumber(lab, "dopamine", 0);       // 多巴胺
            v[12] = getJsonNumber(lab, "lactate", 1.5);      // 乳酸
            v[13] = getJsonNumber(lab, "wbc", 8.0);          // 白细胞
            v[14] = getJsonNumber(lab, "pf_ratio", 350);     // 氧合指数
        } else {
            v[8] = 1.0; v[9] = 200; v[10] = 0.8; v[11] = 0;
            v[12] = 1.5; v[13] = 8.0; v[14] = 350;
        }

        // SOFA
        v[15] = sc.getSofaAdmission() != null ? sc.getSofaAdmission() : 4.0;

        return v;
    }

    /**
     * 检索 Top-N 相似病例
     * @param allianceId 联盟 id
     * @param queryVec 16 维查询向量（新患者特征）
     * @param drgFilter 可选：限定 DRG 编码
     * @param topN 取 Top N
     * @param excludeHospitalId 排除的院区（默认排除当前院，避免查到自己的）
     */
    public List<SimilarHit> searchTopN(long allianceId, double[] queryVec, String drgFilter,
                                       int topN, Long excludeHospitalId) {
        long t0 = System.currentTimeMillis();
        List<SharedCase> pool = sharedCaseRepo.findAll().stream()
            .filter(s -> s.getAllianceId().equals(allianceId))
            .filter(s -> drgFilter == null || drgFilter.equals(s.getDrgCode()))
            .filter(s -> excludeHospitalId == null || !s.getSourceHospital().equals(excludeHospitalId))
            .collect(Collectors.toList());

        if (pool.isEmpty()) {
            log.info("【相似检索】联盟 {} 共享池为空", allianceId);
            return Collections.emptyList();
        }

        // 加载所有索引（生产环境应在 DB 中预计算）
        List<Long> ids = pool.stream().map(SharedCase::getId).collect(Collectors.toList());
        Map<Long, SimilarIndex> idxMap = similarIndexRepo.findBySharedCaseIdIn(ids).stream()
            .collect(Collectors.toMap(SimilarIndex::getSharedCaseId, i -> i, (a, b) -> a));
        Map<Long, SharedCase> caseMap = pool.stream()
            .collect(Collectors.toMap(SharedCase::getId, c -> c, (a, b) -> a));

        // 计算查询向量的范数
        double qNorm = 0;
        for (double v : queryVec) qNorm += v * v;
        qNorm = Math.sqrt(qNorm);

        // 余弦相似度排序
        List<SimilarHit> hits = new ArrayList<>();
        for (SharedCase sc : pool) {
            double[] v = buildVector(sc);
            double dot = 0, n = 0;
            for (int i = 0; i < 16; i++) {
                dot += v[i] * queryVec[i];
                n += v[i] * v[i];
            }
            n = Math.sqrt(n);
            double sim = (qNorm * n == 0) ? 0 : dot / (qNorm * n);
            hits.add(new SimilarHit(sc, sim));
        }
        hits.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        if (hits.size() > topN) hits = hits.subList(0, topN);
        long cost = System.currentTimeMillis() - t0;
        log.info("【相似检索】联盟 {} DRG={} 池容量={} TopN={} 耗时={}ms", allianceId, drgFilter, pool.size(), topN, cost);
        return hits;
    }

    private static double getJsonNumber(JsonNode node, String field, double def) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return def;
        if (v.isNumber()) return v.asDouble();
        try { return Double.parseDouble(v.asText()); } catch (Exception e) { return def; }
    }

    /** 检索结果封装 */
    public static class SimilarHit {
        public final SharedCase sharedCase;
        public final double similarity;
        public SimilarHit(SharedCase s, double sim) { this.sharedCase = s; this.similarity = sim; }
    }
}
