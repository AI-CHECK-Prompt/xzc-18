package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "bed")
public class Bed {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "ward_id", nullable = false) private Long wardId;
    @Column(nullable = false) private String code;
    private String status;            // IDLE/OCCUPIED/CLEANING
    @Column(name = "patient_id") private Long patientId;
}
