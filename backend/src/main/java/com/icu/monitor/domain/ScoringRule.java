package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "scoring_rule")
public class ScoringRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(nullable = false) private String code;        // MEWS / SOFA / CUSTOM
    @Column(nullable = false) private String name;
    @Column(name = "drl_content", columnDefinition = "TEXT", nullable = false) private String drlContent;
    private Integer version;
    private Boolean enabled;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
