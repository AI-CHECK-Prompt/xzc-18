package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "alert_escalation_policy")
public class AlertEscalationPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(nullable = false) private String name;
    @Column(name = "dedup_window_sec") private Integer dedupWindowSec;
    @Column(name = "escalation_count") private Integer escalationCount;
    @Column(name = "escalation_sec") private Integer escalationSec;
    @Column(name = "silence_enabled") private Boolean silenceEnabled;
    @Column(name = "cross_check_metric") private String crossCheckMetric;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
