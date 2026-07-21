package com.icu.monitor.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icu.monitor.domain.alliance.*;
import com.icu.monitor.repository.alliance.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 联合质控服务
 * <p>
 * 核心能力：
 * 1. 按 (联盟, 院区, DRG, 季度) 聚合死亡/感染/平均住院日/SOFA 曲线
 * 2. 跨院区同 DRG 对比（自动找出"明显高于联盟均值"的院区）
 * 3. 生成季度联合报告
 */
@Service
public class JointQcService {
    private static final Logger log = LoggerFactory.getLogger(JointQcService.class);

    @Autowired private QcMetricRepo qcMetricRepo;
    @Autowired private JointQcDao jointQcDao;
    @Autowired private SharedCaseRepo sharedCaseRepo;
    @Autowired private JointReportRepo jointReportRepo;
    @Autowired private ObjectMapper om;

    /**
     * 从共享池聚合生成跨院区质控指标
     * @param allianceId 联盟 id
     * @param drgCode DRG
     * @param periodQuarter 季度，如 "2026Q3"
     */
    @Transactional
    public List<QcMetric> aggregateByDrg(long allianceId, String drgCode, String periodQuarter) {
        List<SharedCase> cases = sharedCaseRepo.findByDrg(allianceId, drgCode);
        if (cases.isEmpty()) {
            log.info("【联合质控】联盟 {} DRG {} 无共享病例，跳过聚合", allianceId, drgCode);
            return Collections.emptyList();
        }

        // 按来源院区分组
        Map<Long, List<SharedCase>> byHosp = new HashMap<>();
        for (SharedCase sc : cases) {
            byHosp.computeIfAbsent(sc.getSourceHospital(), k -> new ArrayList<>()).add(sc);
        }

        List<QcMetric> result = new ArrayList<>();
        for (Map.Entry<Long, List<SharedCase>> e : byHosp.entrySet()) {
            long hospId = e.getKey();
            List<SharedCase> list = e.getValue();
            int caseCount = list.size();
            int deathCount = (int) list.stream()
                .filter(s -> "DECEASED".equals(s.getOutcome())).count();
            int infectionCount = (int) list.stream()
                .filter(SharedCase::getInfectionFlag).count();
            double avgLos = list.stream()
                .filter(s -> s.getLosDays() != null)
                .mapToInt(SharedCase::getLosDays).average().orElse(0);

            // SOFA 曲线：聚合每位患者的真实每日 SOFA（按入院天数偏移 Day 0..7）。
            // 关键：必须用 shared_case.sofa_daily_curve 中存储的真实评分，不允许任何"模拟生成"。
            //   - 若 sofa_daily_curve 为 null/空：只用 sofa_admission 作为 Day0 计入（不补后续天数）
            //   - 若数组长度 < 8：仅对存在的下标累加（避免越界）
            //   - 每日分别取样本均值，未提供该日数据的患者不计入该日
            double[] sofaSum = new double[8];
            int[] sofaCount = new int[8];
            for (SharedCase sc : list) {
                double[] daily = readDailySofa(sc);
                for (int d = 0; d < 8; d++) {
                    double v = daily[d];
                    if (Double.isNaN(v)) continue;
                    sofaSum[d] += v;
                    sofaCount[d]++;
                }
            }
            ArrayNode curve = om.createArrayNode();
            for (int d = 0; d < 8; d++) {
                ObjectNode o = om.createObjectNode();
                o.put("day", d);
                if (sofaCount[d] > 0) {
                    double avg = sofaSum[d] / sofaCount[d];
                    o.put("avg", Math.round(avg * 100.0) / 100.0);
                    o.put("n", sofaCount[d]);
                } else {
                    // 当日无真实数据：不再用任何公式伪造，置 null 让前端识别"数据缺失"
                    o.putNull("avg");
                    o.put("n", 0);
                }
                curve.add(o);
            }

            // 查重（联盟+院区+DRG+季度）
            QcMetric m = qcMetricRepo.findByAllianceIdAndDrgCodeAndPeriodQuarter(allianceId, drgCode, periodQuarter)
                .stream().filter(x -> x.getHospitalId().equals(hospId)).findFirst().orElse(new QcMetric());
            m.setAllianceId(allianceId);
            m.setHospitalId(hospId);
            m.setDrgCode(drgCode);
            m.setPeriodQuarter(periodQuarter);
            m.setCaseCount(caseCount);
            m.setDeathCount(deathCount);
            m.setMortalityRate(caseCount > 0 ? Math.round(deathCount * 10000.0 / caseCount) / 10000.0 : 0);
            m.setAvgLosDays(Math.round(avgLos * 100) / 100.0);
            m.setInfectionCount(infectionCount);
            m.setInfectionRate(caseCount > 0 ? Math.round(infectionCount * 10000.0 / caseCount) / 10000.0 : 0);
            m.setAvgSofaCurve(curve);
            m.setUpdatedAt(OffsetDateTime.now());
            result.add(qcMetricRepo.save(m));
        }
        log.info("【联合质控】联盟 {} DRG {} {} 聚合完成：{} 个院区", allianceId, drgCode, periodQuarter, result.size());
        return result;
    }

    /** 当前季度字符串，如 "2026Q3" */
    public String currentQuarter() {
        java.time.LocalDate d = java.time.LocalDate.now();
        int q = (d.getMonthValue() - 1) / 3 + 1;
        return d.getYear() + "Q" + q;
    }

    /**
     * 从一条共享病例读取 8 元素每日 SOFA 数组（Day 0..7）。
     * 真实数据来源：shared_case.sofa_daily_curve（JSONB 数组）。若不存在，仅用 sofa_admission 作 Day0，
     * 其它天返回 NaN 表示"无真实数据"（聚合端据此跳过，绝不模拟）。
     * 之所以不允许任何"补全/平滑"：SOFA 曲线的真实性是判别"院区病情演化异常"的核心依据，
     * 任何减分衰减/线性插值都会让"第 3 天反弹"这类真实信号被掩盖。
     */
    private static double[] readDailySofa(SharedCase sc) {
        double[] daily = new double[8];
        java.util.Arrays.fill(daily, Double.NaN);
        JsonNode node = sc.getSofaDailyCurve();
        if (node != null && node.isArray()) {
            int n = Math.min(8, node.size());
            for (int i = 0; i < n; i++) {
                JsonNode v = node.get(i);
                if (v == null || v.isNull()) continue;
                if (v.isNumber()) daily[i] = v.doubleValue();
            }
        }
        // 兜底：若 Day0 缺失但 sofa_admission 有值，用 sofa_admission 填 Day0
        if (Double.isNaN(daily[0]) && sc.getSofaAdmission() != null) {
            daily[0] = sc.getSofaAdmission();
        }
        return daily;
    }

    /**
     * 跨院区同 DRG 对比
     * 返回结构：
     * {
     *   drgCode, periodQuarter,
     *   hospitals: [{hospitalId, hospitalName, caseCount, deathCount, mortalityRate, ...}],
     *   allianceAvg: 0.18,
     *   outlier: hospitalId  // 死亡率显著高于联盟均值的院区
     * }
     */
    public Map<String, Object> compareByDrg(long allianceId, String drgCode, String quarter) {
        List<Map<String, Object>> rows = jointQcDao.mortalityByDrgAndHospital(allianceId, drgCode, quarter);
        int totalCases = rows.stream().mapToInt(r -> ((Number) r.get("case_count")).intValue()).sum();
        int totalDeaths = rows.stream().mapToInt(r -> ((Number) r.get("death_count")).intValue()).sum();
        double allianceAvg = totalCases > 0 ? (double) totalDeaths / totalCases : 0;

        Long outlier = null;
        double maxDiff = 0;
        for (Map<String, Object> r : rows) {
            double mr = ((Number) r.get("mortality_rate")).doubleValue();
            double diff = mr - allianceAvg;
            if (diff > maxDiff && diff > 0.02) {  // 显著高出 2 个百分点
                maxDiff = diff;
                outlier = ((Number) r.get("hospital_id")).longValue();
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("allianceId", allianceId);
        out.put("drgCode", drgCode);
        out.put("periodQuarter", quarter);
        out.put("hospitals", rows);
        out.put("allianceAvgMortality", Math.round(allianceAvg * 10000) / 10000.0);
        out.put("outlierHospitalId", outlier);
        out.put("outlierDelta", Math.round(maxDiff * 10000) / 10000.0);
        return out;
    }

    /** DRG 全维度对比 */
    public List<Map<String, Object>> drgBreakdown(long allianceId, String quarter) {
        return jointQcDao.drgBreakdown(allianceId, quarter);
    }

    /**
     * 入选"重点关注高死亡率 DRG"的最小样本量阈值。
     * <p>
     * 设为 10 是为了避免小样本极端值（如 1 死 / 2 病例 = 50% 死亡率）被打成"高死亡率 DRG"，
     * 造成整改资源错配（按 Pareto 临床经验，季度内 ≥10 例才有统计意义）。
     */
    static final int MIN_SAMPLE_FOR_HIGH_MORTALITY = 10;

    /**
     * 从 drgBreakdown 列表中选出"死亡率最高 DRG"。
     * <p>
     * 规则：
     *   1) 只在 total_cases >= minSample 的 DRG 中选（避免"1/2=50%"这种小样本极端值）
     *   2) 当 mortality 相同时，优先选 total_cases 较大的（更可靠的信号）
     *   3) 若全部 DRG 都不满足最小样本（演示场景/数据极少），返回 null，由调用方决定兜底策略
     *   4) breakdown 为空或 null 时返回 null
     * <p>
     * 该方法为 static + package-private，便于纯逻辑单测（无 Spring/DB 依赖）。
     * 历史 bug（2026-07-21）：generateReport 此前直接取 breakdown.get(0)，但 drgBreakdown
     * DAO 按 total_cases DESC 排序，导致"高死亡率"实际是"病例数最多"，
     * 掩盖了真正高死亡率的 DRG。
     */
    static Map<String, Object> selectHighMortalityDrg(List<Map<String, Object>> breakdown, int minSample) {
        if (breakdown == null || breakdown.isEmpty()) return null;
        Map<String, Object> best = null;
        for (Map<String, Object> d : breakdown) {
            int totalCases = ((Number) d.get("total_cases")).intValue();
            if (totalCases < minSample) continue;
            double mortality = ((Number) d.get("alliance_mortality")).doubleValue();
            if (best == null) {
                best = d;
                continue;
            }
            double bestMort = ((Number) best.get("alliance_mortality")).doubleValue();
            int bestCases = ((Number) best.get("total_cases")).intValue();
            if (mortality > bestMort || (mortality == bestMort && totalCases > bestCases)) {
                best = d;
            }
        }
        return best;
    }

    /**
     * 生成季度联合质控报告
     */
    @Transactional
    public JointReport generateReport(long allianceId, String periodQuarter) {
        log.info("【联合质控】开始生成报告 alliance={} quarter={}", allianceId, periodQuarter);
        List<Map<String, Object>> breakdown = drgBreakdown(allianceId, periodQuarter);
        if (breakdown.isEmpty()) {
            log.info("【联合质控】无数据，跳过报告生成");
            return null;
        }

        // ============================================================
        // 关键修复（历史 bug：2026-07-21）
        // 此前 generateReport 用 breakdown.get(0) 取"高死亡率 DRG"，但 drgBreakdown
        // DAO 是按 total_cases DESC 排序的，导致"高死亡率"实际是"病例数最多"。
        // 现象：综合三甲医院的"肺部感染 DRG"病例数最多且治愈率很高（死亡率 2%），
        //       始终排在首部，掩盖了真正死亡率 18% 的某小样本 DRG（如"急性胰腺炎重症"）。
        // 修复：分别取"病例数最多 DRG"（规模维度）和"死亡率最高 DRG"（重点关注维度），
        //       并对后者加最小样本量阈值，避免小样本极端值浪费整改资源。
        // ============================================================

        // (a) 病例数最多 DRG：DAO 默认按 total_cases DESC 排序，首项即
        Map<String, Object> mostCasesDrg = breakdown.get(0);
        String mostCasesDrgCode = (String) mostCasesDrg.get("drg_code");
        int mostCasesTotal = ((Number) mostCasesDrg.get("total_cases")).intValue();
        double mostCasesMortality = ((Number) mostCasesDrg.get("alliance_mortality")).doubleValue();

        // (b) 死亡率最高 DRG：在 total_cases >= MIN_SAMPLE_FOR_HIGH_MORTALITY 的 DRG 中按 alliance_mortality 选最大
        //    委派给纯逻辑方法 selectHighMortalityDrg，便于单测
        Map<String, Object> highMortDrg = selectHighMortalityDrg(breakdown, MIN_SAMPLE_FOR_HIGH_MORTALITY);
        // 兜底：若所有 DRG 都不满足最小样本（演示场景/数据极少），回退到全体中死亡率最高的，
        // 避免 report 没有 highMortDrg 导致后续 JSON 节点为 null
        if (highMortDrg == null) {
            log.warn("【联合质控】所有 DRG 病例数均 < {}，回退到全体中死亡率最高的 DRG", MIN_SAMPLE_FOR_HIGH_MORTALITY);
            highMortDrg = breakdown.stream()
                .max(Comparator.comparing(d -> ((Number) d.get("alliance_mortality")).doubleValue()))
                .orElse(mostCasesDrg);
        }
        String highDrg = (String) highMortDrg.get("drg_code");
        double highMortality = ((Number) highMortDrg.get("alliance_mortality")).doubleValue();
        int highMortTotalCases = ((Number) highMortDrg.get("total_cases")).intValue();
        int highMortTotalDeaths = ((Number) highMortDrg.get("total_deaths")).intValue();

        log.info("【联合质控】mostCasesDrg={} cases={} mortality={}; highMortalityDrg={} mortality={} cases={} deaths={}",
            mostCasesDrgCode, mostCasesTotal, mostCasesMortality,
            highDrg, highMortality, highMortTotalCases, highMortTotalDeaths);

        ArrayNode highlights = om.createArrayNode();
        // 重点 1（规模维度）：病例数最多 DRG —— 仅描述规模，不作为整改对象
        ObjectNode h0 = om.createObjectNode();
        h0.put("type", "TOP_VOLUME_DRG");
        h0.put("drgCode", mostCasesDrgCode);
        h0.put("totalCases", mostCasesTotal);
        h0.put("mortality", mostCasesMortality);
        h0.put("note", "联盟中病例数最多的 DRG，反映规模，不直接作为重点关注");
        highlights.add(h0);
        // 重点 2（重点关注维度）：死亡率最高 DRG —— 整改资源应投到此处
        ObjectNode h1 = om.createObjectNode();
        h1.put("type", "HIGH_MORTALITY_DRG");
        h1.put("drgCode", highDrg);
        h1.put("mortality", highMortality);
        h1.put("totalCases", highMortTotalCases);
        h1.put("totalDeaths", highMortTotalDeaths);
        h1.put("minSample", MIN_SAMPLE_FOR_HIGH_MORTALITY);
        h1.put("note", "联盟中死亡率最高的 DRG（已过滤总例数 < " + MIN_SAMPLE_FOR_HIGH_MORTALITY
            + " 的样本，避免小样本极端值）");
        highlights.add(h1);

        // 跨院区对比
        ObjectNode drgBreakdownNode = om.createObjectNode();
        for (Map<String, Object> d : breakdown) {
            String drg = (String) d.get("drg_code");
            ArrayNode hospArr = om.createArrayNode();
            List<Map<String, Object>> hospitals = jointQcDao.mortalityByDrgAndHospital(
                allianceId, drg, periodQuarter);
            for (Map<String, Object> r : hospitals) {
                ObjectNode hh = om.createObjectNode();
                hh.putPOJO("hospitalId", r.get("hospital_id"));
                hh.putPOJO("hospitalName", r.get("hospital_name"));
                hh.putPOJO("mortality", r.get("mortality_rate"));
                hh.putPOJO("los", r.get("avg_los_days"));
                hh.putPOJO("infection", r.get("infection_rate"));
                hospArr.add(hh);
            }
            drgBreakdownNode.set(drg, hospArr);
        }

        // 改进建议
        ArrayNode actions = om.createArrayNode();
        // 优先级 HIGH：整改对象是高死亡率 DRG（不是病例数最多 DRG，避免与摘要/重点 1 混淆）
        ObjectNode a1 = om.createObjectNode();
        a1.put("drgCode", highDrg);
        a1.put("action", "对高死亡率 DRG 开展专项根因复盘，对比联盟最优院区诊疗路径");
        a1.put("mortality", highMortality);
        a1.put("totalCases", highMortTotalCases);
        a1.put("priority", "HIGH");
        actions.add(a1);
        ObjectNode a2 = om.createObjectNode();
        a2.put("action", "统一联盟内 SOFA 评分采集频率与字段口径，减少数据漂移");
        a2.put("priority", "MEDIUM");
        actions.add(a2);

        // 落库
        JointReport rep = jointReportRepo.findByAllianceIdAndPeriodQuarter(allianceId, periodQuarter)
            .orElse(new JointReport());
        rep.setAllianceId(allianceId);
        rep.setPeriodQuarter(periodQuarter);
        rep.setTitle("联合质控季度报告 - " + periodQuarter);
        // 摘要明确区分两个维度，避免"高死亡率 DRG 实际是病例数最多"的旧 bug 误导决策者
        rep.setSummary("联盟共覆盖 " + breakdown.size() + " 个 DRG 编码；" +
            "病例数最多 DRG=" + mostCasesDrgCode + "(病例=" + mostCasesTotal + "), " +
            "死亡率最高 DRG=" + highDrg + "(死亡率=" +
            Math.round(highMortality * 10000) / 100.0 + "%, 病例=" + highMortTotalCases + ")");
        rep.setHighlights(highlights);
        rep.setDrgBreakdown(drgBreakdownNode);
        rep.setActionItems(actions);
        rep.setGeneratedAt(OffsetDateTime.now());
        JointReport saved = jointReportRepo.save(rep);
        log.info("【联合质控】报告生成完成 id={}", saved.getId());
        return saved;
    }
}
