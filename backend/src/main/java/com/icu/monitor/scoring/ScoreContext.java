package com.icu.monitor.scoring;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 评分输入上下文（一次评估/一个时间窗）。
 * 字段覆盖 MEWS（5项）与 SOFA（6 个器官系统），医院自定义规则可读取任意子集。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreContext {
    private String ruleCode;          // MEWS / SOFA / CUSTOM
    private Long patientId;
    private Long bedId;
    private java.time.OffsetDateTime time;
    // MEWS
    private Double sbp;               // 收缩压
    private Double hr;                // 心率
    private Double rr;                // 呼吸
    private Double temp;              // 体温
    private String avpu;              // A/V/P/U
    // SOFA
    private Double pfRatio;           // PaO2/FiO2
    private Double plt;               // 血小板
    private Double bilirubin;         // 胆红素
    private Double dopamine;          // 多巴胺剂量
    private Double dobutamine;        // 多巴酚丁胺
    private Double map;               // 平均动脉压
    private Integer gcs;              // 格拉斯哥昏迷评分
    private Double creatinine;        // 肌酐
}
