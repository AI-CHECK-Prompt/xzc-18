package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "hospital")
public class Hospital {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
