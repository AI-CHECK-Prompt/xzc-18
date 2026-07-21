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
 * <p>
 * 严重程度轴约束（clinical safety）：
 * - 仅靠"特征向量方向"对齐会忽略 SOFA 量级。SOFA=3 的轻症患者
 *   在余弦意义上可能与 SOFA=14 的重症患者方向接近（其它维度都"正常"），
 *   导致把"轻症成功路径"作为 evidenceLevel=C 推荐给当前重症患者。
 * - 因此叠加"严重程度带（severity band）"约束：
 *   * 4 个 band：[0,4) 轻症、[4,8) 中症、[8,12) 重症、[12,∞) 极重症
 *   * |Δband| >= 2（轻症 vs 极重症 / 中症 vs 极重症等）：硬阈值，sim 置 0
 *     → 防止把"轻症成功路径"推到重症患者身上
 *   * |Δband| = 1：相邻带，软惩罚（×0.55）
 *   * 同带内：再按 |Δsofa| 线性衰减（每 1 分 -5%，封底 0.5）
 */
@Service
public class SimilarCaseService {
    private static final Logger log = LoggerFactory.getLogger(SimilarCaseService.class);

    /** SOFA 严重程度带分界（4 个带：0/1/2/3） */
    private static final double[] SOFA_BAND_EDGES = {0.0, 4.0, 8.0, 12.0};
    /** 相邻带的软惩罚系数（gap==1 时使用） */
    private static final double ADJACENT_BAND_PENALTY = 0.55;
    /** 同带内每 1 分 SOFA 差的衰减系数（再叠加） */
    private static final double SAME_BAND_SOFA_DECAY = 0.05;
    /** 同带内惩罚的下限（保证 0.5 以上，不会把同带高分也压成 0） */
    private static final double SAME_BAND_PENALTY_FLOOR = 0.5;
    /** 硬阈值的最小 band 差（>= 视为严重程度不匹配） */
    private static final int HARD_DROP_BAND_GAP = 2;

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
     * <p>
     * 排序评分 = 余弦相似度 × 严重程度轴惩罚（clinical safety）：
     * 1. 先算 16 维向量的余弦相似度（只看特征向量"方向"）
     * 2. 再叠加 SOFA 严重程度带约束：硬阈值丢弃跨带候选、相邻带软惩罚、同带内按 |Δsofa| 衰减
     * 3. 排序取 Top-N；被丢弃的候选会写日志，便于 clinical safety review 定位"真正相似的高 SOFA 病例"
     *
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

        // 严重程度轴信息（queryVec[15] 即 SOFA 评分）
        double querySofa = queryVec.length > 15 ? queryVec[15] : 0.0;
        int queryBand = sofaBand(querySofa);

        // 余弦相似度 × 严重程度惩罚
        List<SimilarHit> hits = new ArrayList<>();
        int droppedOutOfBand = 0;        // 硬阈值丢弃（gap>=2）
        int demotedAdjacent = 0;         // 相邻带被降权（gap==1）
        for (SharedCase sc : pool) {
            double[] v = buildVector(sc);
            double dot = 0, n = 0;
            for (int i = 0; i < 16; i++) {
                dot += v[i] * queryVec[i];
                n += v[i] * v[i];
            }
            n = Math.sqrt(n);
            double rawSim = (qNorm * n == 0) ? 0 : dot / (qNorm * n);

            // 严重程度轴约束
            double candSofa = v[15];
            int candBand = sofaBand(candSofa);
            int gap = Math.abs(queryBand - candBand);
            SeverityMatch match;
            double penalty;
            if (gap >= HARD_DROP_BAND_GAP) {
                // 硬阈值：严重程度轴方向不匹配，直接丢弃
                // 避免把"轻症成功路径"作为 evidenceLevel=C 推荐给重症患者
                match = SeverityMatch.OUT_OF_BAND;
                penalty = 0.0;
                droppedOutOfBand++;
            } else if (gap == 1) {
                // 相邻带：软惩罚
                match = SeverityMatch.ADJACENT;
                penalty = ADJACENT_BAND_PENALTY;
                demotedAdjacent++;
            } else {
                // 同带：按 |Δsofa| 线性衰减，封底 0.5
                match = SeverityMatch.MATCH;
                double absDelta = Math.abs(querySofa - candSofa);
                penalty = Math.max(SAME_BAND_PENALTY_FLOOR,
                    1.0 - SAME_BAND_SOFA_DECAY * absDelta);
            }
            double finalSim = rawSim * penalty;
            // 保留原 rawSim 用于临床安全审计（前端可见）
            hits.add(new SimilarHit(sc, finalSim, rawSim, queryBand, candBand, querySofa, candSofa, match));
        }
        // 按"被严重程度惩罚后"的最终相似度排序
        hits.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        if (hits.size() > topN) hits = hits.subList(0, topN);
        long cost = System.currentTimeMillis() - t0;
        log.info("【相似检索】联盟 {} DRG={} 池容量={} querySOFA={} band={} TopN={} 硬丢弃={} 相邻带降权={} 耗时={}ms",
            allianceId, drgFilter, pool.size(), querySofa, queryBand, topN, droppedOutOfBand, demotedAdjacent, cost);
        return hits;
    }

    /**
     * SOFA 严重程度带
     * band=0 轻症 [0,4)；band=1 中症 [4,8)；band=2 重症 [8,12)；band=3 极重症 [12,∞)
     */
    public static int sofaBand(double sofa) {
        if (sofa < SOFA_BAND_EDGES[1]) return 0;
        if (sofa < SOFA_BAND_EDGES[2]) return 1;
        if (sofa < SOFA_BAND_EDGES[3]) return 2;
        return 3;
    }

    private static double getJsonNumber(JsonNode node, String field, double def) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return def;
        if (v.isNumber()) return v.asDouble();
        try { return Double.parseDouble(v.asText()); } catch (Exception e) { return def; }
    }

    /** 严重程度轴匹配标签（用于 clinical safety review） */
    public enum SeverityMatch {
        /** 同带，无惩罚 */
        MATCH,
        /** 相邻带（gap=1），已软惩罚 ×0.55 */
        ADJACENT,
        /** 跨带（gap>=2），硬阈值丢弃；保留枚举值以便审计 */
        OUT_OF_BAND
    }

    /** 检索结果封装 */
    public static class SimilarHit {
        public final SharedCase sharedCase;
        /** 经严重程度轴惩罚后的最终相似度（用于排序与展示） */
        public final double similarity;
        /** 原始余弦相似度（未叠加严重程度惩罚），用于临床安全审计 */
        public final double rawSimilarity;
        /** 查询 SOFA 所属 band（0=轻症/1=中症/2=重症/3=极重症） */
        public final int queryBand;
        /** 候选 SOFA 所属 band */
        public final int candidateBand;
        public final double querySofa;
        public final double candidateSofa;
        public final SeverityMatch severityMatch;

        public SimilarHit(SharedCase s, double sim) { this(s, sim, sim, -1, -1, 0, 0, SeverityMatch.MATCH); }

        public SimilarHit(SharedCase s, double sim, double rawSim,
                          int queryBand, int candBand,
                          double querySofa, double candSofa,
                          SeverityMatch match) {
            this.sharedCase = s;
            this.similarity = sim;
            this.rawSimilarity = rawSim;
            this.queryBand = queryBand;
            this.candidateBand = candBand;
            this.querySofa = querySofa;
            this.candidateSofa = candSofa;
            this.severityMatch = match;
        }
    }
}
