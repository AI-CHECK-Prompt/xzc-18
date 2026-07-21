package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "qc_metric")
public class QcMetric {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "drg_code", nullable = false) private String drgCode;
    @Column(name = "period_quarter", nullable = false) private String periodQuarter; // 2026Q3
    @Column(name = "case_count") private Integer caseCount = 0;
    @Column(name = "death_count") private Integer deathCount = 0;
    @Column(name = "mortality_rate") private Double mortalityRate = 0.0;
    @Column(name = "avg_los_days") private Double avgLosDays = 0.0;
    @Column(name = "infection_count") private Integer infectionCount = 0;
    @Column(name = "infection_rate") private Double infectionRate = 0.0;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avg_sofa_curve", columnDefinition = "jsonb") private JsonNode avgSofaCurve;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;
}
