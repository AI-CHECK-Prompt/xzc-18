package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "whatif_session")
public class WhatIfSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_patient_id") private Long sourcePatientId;
    @Column(name = "shared_case_id") private Long sharedCaseId;
    @Column(name = "plan_template_id", nullable = false) private Long planTemplateId;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "actual_outcome") private String actualOutcome;
    @Column(name = "predicted_outcome") private String predictedOutcome;
    @Column(name = "mortality_delta") private Double mortalityDelta;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timeline_delta", columnDefinition = "jsonb") private JsonNode timelineDelta;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_chain", columnDefinition = "jsonb") private JsonNode evidenceChain;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
