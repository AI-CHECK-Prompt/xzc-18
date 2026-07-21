package com.icu.monitor.domain.alliance;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "alliance_member")
public class AllianceMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alliance_id", nullable = false) private Long allianceId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    private String role = "MEMBER";
    @Column(name = "joined_at") private OffsetDateTime joinedAt;
}
