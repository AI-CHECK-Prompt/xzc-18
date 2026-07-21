package com.icu.monitor.repository.alliance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 跨院区聚合查询：DRG 分组、SOFA 曲线、感染率等指标
 * 走 JdbcTemplate 直接出 SQL 聚合，性能最佳
 */
@Repository
public class JointQcDao {
    private final JdbcTemplate jdbc;
    public JointQcDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 跨院区同 DRG 的死亡率对比（按医院聚合） */
    public List<Map<String, Object>> mortalityByDrgAndHospital(long allianceId, String drgCode, String quarter) {
        return jdbc.queryForList(
            "SELECT m.hospital_id, h.name AS hospital_name, h.code AS hospital_code, " +
            "       m.case_count, m.death_count, m.mortality_rate, " +
            "       m.avg_los_days, m.infection_count, m.infection_rate " +
            "FROM qc_metric m JOIN hospital h ON h.id = m.hospital_id " +
            "WHERE m.alliance_id=? AND m.drg_code=? AND m.period_quarter=? " +
            "ORDER BY m.mortality_rate DESC",
            allianceId, drgCode, quarter);
    }

    /** 联盟内所有 DRG 的整体对比 */
    public List<Map<String, Object>> drgBreakdown(long allianceId, String quarter) {
        return jdbc.queryForList(
            "SELECT m.drg_code, " +
            "       SUM(m.case_count)  AS total_cases, " +
            "       SUM(m.death_count) AS total_deaths, " +
            "       CASE WHEN SUM(m.case_count) > 0 " +
            "            THEN ROUND(SUM(m.death_count)::numeric / SUM(m.case_count), 4) " +
            "            ELSE 0 END AS alliance_mortality, " +
            "       ROUND(AVG(m.avg_los_days)::numeric, 2) AS avg_los, " +
            "       CASE WHEN SUM(m.case_count) > 0 " +
            "            THEN ROUND(SUM(m.infection_count)::numeric / SUM(m.case_count), 4) " +
            "            ELSE 0 END AS infection_rate " +
            "FROM qc_metric m " +
            "WHERE m.alliance_id=? AND m.period_quarter=? " +
            "GROUP BY m.drg_code " +
            "ORDER BY total_cases DESC",
            allianceId, quarter);
    }

    /** SOFA 评分变化曲线（按 DRG + 医院） */
    public List<Map<String, Object>> sofaCurve(long allianceId, String drgCode, String quarter) {
        return jdbc.queryForList(
            "SELECT m.hospital_id, h.name AS hospital_name, m.avg_sofa_curve " +
            "FROM qc_metric m JOIN hospital h ON h.id = m.hospital_id " +
            "WHERE m.alliance_id=? AND m.drg_code=? AND m.period_quarter=?",
            allianceId, drgCode, quarter);
    }
}
