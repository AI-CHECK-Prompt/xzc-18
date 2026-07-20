package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "playback_session")
public class PlaybackSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "bed_id", nullable = false) private Long bedId;
    @Column(name = "patient_id") private Long patientId;
    @Column(name = "trigger_alert_id") private Long triggerAlertId;
    @Column(name = "start_at", nullable = false) private OffsetDateTime startAt;
    @Column(name = "end_at", nullable = false) private OffsetDateTime endAt;
    private String status;                               // ACTIVE/ARCHIVED
    private String summary;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
