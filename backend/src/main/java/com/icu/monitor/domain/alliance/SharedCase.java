package com.icu.monitor.domain.alliance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "shared_case")
public class SharedCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "source_hospital", nullable = false) private Long sourceHospital;
    @Column(name = "pool_patient_key", nullable = false) private String poolPatientKey;
    @Column(name = "drg_code", nullable = false) private String drgCode;
    @Column(name = "mdc_code") private String mdcCode;
    @Column(name = "age_band") private String ageBand;
    private String gender;
    @Column(name = "sofa_admission") private Double sofaAdmission;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sofa_daily_curve", columnDefinition = "jsonb") private JsonNode sofaDailyCurve;
    @Column(name = "apache_admission") private Double apacheAdmission;
    @Column(name = "diagnosis_text") private String diagnosisText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vitals_summary", columnDefinition = "jsonb") private JsonNode vitalsSummary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lab_summary", columnDefinition = "jsonb") private JsonNode labSummary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "treatment_path", columnDefinition = "jsonb") private JsonNode treatmentPath;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rescue_events", columnDefinition = "jsonb") private JsonNode rescueEvents;
    private String outcome;                       // SURVIVED/TRANSFERRED/DECEASED
    @Column(name = "los_days") private Integer losDays;
    @Column(name = "infection_flag") private Boolean infectionFlag = Boolean.FALSE;
    @Column(name = "admission_at", nullable = false) private OffsetDateTime admissionAt;
    @Column(name = "shared_at") private OffsetDateTime sharedAt;
}
