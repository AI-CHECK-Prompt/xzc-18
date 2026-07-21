package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "joint_report")
public class JointReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "period_quarter", nullable = false) private String periodQuarter;
    @Column(nullable = false) private String title;
    private String summary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private JsonNode highlights;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "drg_breakdown", columnDefinition = "jsonb") private JsonNode drgBreakdown;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_items", columnDefinition = "jsonb") private JsonNode actionItems;
    @Column(name = "generated_at") private OffsetDateTime generatedAt;
}
