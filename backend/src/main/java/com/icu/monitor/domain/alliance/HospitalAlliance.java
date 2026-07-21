package com.icu.monitor.domain.alliance;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "hospital_alliance")
public class HospitalAlliance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false) private String code;
    @Column(nullable = false) private String name;
    private String description;
    @Column(name = "deid_enabled") private Boolean deidEnabled = Boolean.TRUE;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
