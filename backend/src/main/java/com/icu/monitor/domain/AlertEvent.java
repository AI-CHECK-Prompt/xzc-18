package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "alert_event")
public class AlertEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private OffsetDateTime time;
    @Column(name = "bed_id", nullable = false) private Long bedId;
    @Column(name = "patient_id") private Long patientId;
    @Column(name = "channel_code") private String channelCode;
    @Column(nullable = false) private String level;       // INFO/WARN/CRITICAL
    @Column(name = "alert_type", nullable = false) private String alertType;
    private Double value;
    private String message;
    private String status;                                // OPEN/ACK/CLOSED/SUPPRESSED
    @Column(name = "dedup_count") private Integer dedupCount;
    @Column(name = "parent_id") private Long parentId;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
