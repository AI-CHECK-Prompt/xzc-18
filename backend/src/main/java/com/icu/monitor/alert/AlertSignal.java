package com.icu.monitor.alert;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

/**
 * 内部告警信号：阈值规则产生，尚未做去重/升级/静默决策。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AlertSignal {
    private OffsetDateTime time;
    private Long bedId;
    private Long patientId;
    private String channelCode;       // HR / SPO2 / SBP / TEMP / RR ...
    private String alertType;          // HR_LOW / SPO2_LOW / HR_PROBE_LOOSE ...
    private String level;              // INFO / WARN / CRITICAL
    private Double value;
    private String message;
    private Integer quality;           // 信号质量
    private Boolean suspectArtifact;   // 是否疑似设备异常（探头脱落等）
}
