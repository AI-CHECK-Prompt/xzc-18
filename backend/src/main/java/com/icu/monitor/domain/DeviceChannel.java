package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "device_channel")
public class DeviceChannel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_id", nullable = false) private Long deviceId;
    @Column(nullable = false) private String code;        // ECG_II / SPO2 / HR
    @Column(name = "display_name", nullable = false) private String displayName;
    private String unit;
    @Column(name = "sample_hz") private Integer sampleHz;
}
