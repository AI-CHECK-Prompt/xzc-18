package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "nursing_record")
public class NursingRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private OffsetDateTime time;
    @Column(name = "patient_id", nullable = false) private Long patientId;
    @Column(name = "bed_id") private Long bedId;
    @Column(name = "nurse_id") private String nurseId;
    private String category;                             // OBSERVATION/INTERVENTION/MEDICATION
    private String content;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
