package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "guideline")
public class Guideline {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "drg_code") private String drgCode;
    @Column(name = "mdc_code") private String mdcCode;
    @Column(nullable = false) private String title;
    @Column(name = "evidence_level", nullable = false) private String evidenceLevel; // A/B/C
    private String source;
    private String url;
    private String summary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_actions", columnDefinition = "jsonb") private JsonNode keyActions;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
