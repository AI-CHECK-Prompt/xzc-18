package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "playback_item")
public class PlaybackItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(nullable = false) private OffsetDateTime time;
    @Column(name = "source_type", nullable = false) private String sourceType; // WAVEFORM/VITAL/ALERT/ORDER/NURSING
    @Column(name = "ref_id") private Long refId;
    @Column(columnDefinition = "jsonb") private String payload;
}
