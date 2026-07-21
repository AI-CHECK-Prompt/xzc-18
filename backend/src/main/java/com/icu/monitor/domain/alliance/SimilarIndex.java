package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "similar_index")
public class SimilarIndex {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "shared_case_id", nullable = false) private Long sharedCaseId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_vector", nullable = false, columnDefinition = "jsonb") private JsonNode featureVector;
    @Column(name = "norm") private Double norm;
    @Column(name = "built_at") private OffsetDateTime builtAt;
}
