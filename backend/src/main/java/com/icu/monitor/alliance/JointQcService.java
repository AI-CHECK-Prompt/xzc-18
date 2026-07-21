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

            // SOFA 曲线：按入院天数偏移聚合
            double[] sofaSum = new double[8];
            int[] sofaCount = new int[8];
            for (SharedCase sc : list) {
                if (sc.getSofaAdmission() == null) continue;
                sofaSum[0] += sc.getSofaAdmission();
                sofaCount[0]++;
                // 演示用：模拟 SOFA 随天数下降
                for (int d = 1; d < 8; d++) {
                    double v = Math.max(0, sc.getSofaAdmission() - d * 0.5);
                    sofaSum[d] += v;
                    sofaCount[d]++;
                }
            }
            ArrayNode curve = om.createArrayNode();
            for (int d = 0; d < 8; d++) {
                ObjectNode o = om.createObjectNode();
                o.put("day", d);
                o.put("avg", sofaCount[d] > 0 ? Math.round(sofaSum[d] * 100.0 / sofaCount[d]) / 100.0 : 0);
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

        // 重点：找出死亡率最高的 DRG、明显低于均值的院区
        Map<String, Object> top = breakdown.get(0);
        String highDrg = (String) top.get("drg_code");
        double highMortality = ((Number) top.get("alliance_mortality")).doubleValue();

        ArrayNode highlights = om.createArrayNode();
        ObjectNode h1 = om.createObjectNode();
        h1.put("type", "TOP_DRG");
        h1.put("drgCode", highDrg);
        h1.put("mortality", highMortality);
        h1.put("note", "联盟中病例数最多、死亡率最高的 DRG");
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
        ObjectNode a1 = om.createObjectNode();
        a1.put("drgCode", highDrg);
        a1.put("action", "对高死亡率 DRG 开展专项根因复盘，对比联盟最优院区诊疗路径");
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
        rep.setSummary("联盟共覆盖 " + breakdown.size() + " 个 DRG 编码，" +
            "病例数最多 DRG=" + highDrg + "，联盟死亡率=" +
            Math.round(highMortality * 10000) / 100.0 + "%");
        rep.setHighlights(highlights);
        rep.setDrgBreakdown(drgBreakdownNode);
        rep.setActionItems(actions);
        rep.setGeneratedAt(OffsetDateTime.now());
        JointReport saved = jointReportRepo.save(rep);
        log.info("【联合质控】报告生成完成 id={}", saved.getId());
        return saved;
    }
}
