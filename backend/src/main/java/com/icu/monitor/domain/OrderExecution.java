package com.icu.monitor.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "order_execution")
public class OrderExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private OffsetDateTime time;
    @Column(name = "patient_id", nullable = false) private Long patientId;
    @Column(name = "bed_id") private Long bedId;
    @Column(name = "order_no", nullable = false) private String orderNo;
    @Column(name = "order_type") private String orderType;     // MED/INSPECT/NURSE
    @Column(name = "item_name") private String itemName;
    private String status;                                    // PENDING/DONE/CANCELLED
    private String executor;
    @Column(name = "created_at") private OffsetDateTime createdAt;
}
