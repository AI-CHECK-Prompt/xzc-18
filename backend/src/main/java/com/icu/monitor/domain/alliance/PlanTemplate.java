package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "plan_template")
public class PlanTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "drg_code") private String drgCode;
    @Column(nullable = false) private String title;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private JsonNode steps;
    @Column(name = "evidence_level", nullable = false) private String evidenceLevel; // A/B/C
    @Column(name = "based_on") private String basedOn;     // GUIDELINE/RCT/SIMILAR_CASE
    @Column(name = "source_url") private String sourceUrl;
    @Column(name = "support_count") private Integer supportCount = 0;
    @Column(name = "success_rate") private Double successRate = 0.0;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
