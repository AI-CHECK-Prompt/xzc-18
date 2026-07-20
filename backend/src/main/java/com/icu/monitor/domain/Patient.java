package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "patient")
public class Patient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(unique = true, nullable = false) private String mrn;
    @Column(name = "name_enc", nullable = false) private byte[] nameEnc;
    @Column(name = "name_mask", nullable = false) private String nameMask;
    private String gender;
    @Column(name = "birth_date") private LocalDate birthDate;
    @Column(name = "id_card_enc") private byte[] idCardEnc;
    @Column(name = "admission_at") private OffsetDateTime admissionAt;
    private String diagnosis;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
