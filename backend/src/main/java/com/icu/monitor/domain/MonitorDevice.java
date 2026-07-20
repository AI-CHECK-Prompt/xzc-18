package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "monitor_device")
public class MonitorDevice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "bed_id") private Long bedId;
    @Column(nullable = false) private String vendor;     // MINDRAY/PHILIPS/GE
    private String model;
    @Column(name = "serial_no", unique = true, nullable = false) private String serialNo;
    @Column(nullable = false) private String protocol;  // HL7_V2 / IHE_PCD / PRIVATE_TCP
    private String ip;
    private Boolean online;
    @Column(name = "last_seen_at") private OffsetDateTime lastSeenAt;
}
