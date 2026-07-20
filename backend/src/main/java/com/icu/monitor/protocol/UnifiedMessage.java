package com.icu.monitor.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 协议适配器输出的统一内部消息 — 进入 Kafka 内部消息总线。
 * 每条消息代表一个采样点（高频）或一条衍生指标（1min）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedMessage {
    @JsonProperty("t")       private OffsetDateTime time;       // 采样时刻
    @JsonProperty("src")     private String sourceProtocol;     // HL7_V2 / IHE_PCD / PRIVATE_TCP
    @JsonProperty("bedId")   private Long bedId;
    @JsonProperty("patId")   private Long patientId;
    @JsonProperty("devSn")   private String deviceSerialNo;
    @JsonProperty("chCode")  private String channelCode;        // HR / SPO2 / SBP / DBP / TEMP / RR / ECG_II ...
    @JsonProperty("val")     private Double valueNum;
    @JsonProperty("wave")    private byte[] valueWave;          // 波形（可空）
    @JsonProperty("qual")    private Integer quality;           // 0-100
    @JsonProperty("kind")    private String kind;               // RAW / METRIC
    @JsonProperty("extra")   private Map<String, Object> extra;
}
