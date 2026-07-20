package com.icu.monitor.scoring;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评分输入上下文（一次评估/一个时间窗）。
 * 字段覆盖 MEWS（5项）与 SOFA（6 个器官系统），医院自定义规则可读取任意子集。
 *
 * 重要（质控案例 2026-08）：
 *  缺通道不得用"最乐观值"兜底，否则会把脓毒症休克患者误判为 NORMAL。
 *  - gcsMissing / missingChannels / incompleteAssessment 显式告诉规则"这次评估不完整"
 *  - 规则必须按"最不利临床假设"补分，并禁止在 incomplete 时输出 NORMAL
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreContext {
    private String ruleCode;          // MEWS / SOFA / CUSTOM
    private Long patientId;
    private Long bedId;
    private OffsetDateTime time;
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
    /**
     * GCS（格拉斯哥昏迷评分，3-15）。
     * 重要：通道缺数据时此字段为 null（不再是 15 兜底），配合 gcsMissing=true
     * 让 DRL 规则按"最不利"补分。
     */
    private Integer gcs;
    private Double creatinine;        // 肌酐

    // ---- 评估完整性元数据（2026-08 质控新增）----

    /** GCS 通道未在 freshness 窗口内上报 → 视为缺失；DRL 须按"中重度神经受损"补分 */
    private boolean gcsMissing;

    /** 评分时刻缺失的关键通道列表（不包含已被 newest sample 刷新的通道） */
    private List<String> missingChannels = new ArrayList<>();

    /** 任意关键通道缺失或患者刚转入 → 评估不完整；DRL 须禁止输出 NORMAL */
    private boolean incompleteAssessment;

    /**
     * 患者转入 ICU / 床位换绑定 后 5 分钟内（前 5 分钟 + 任何缺通道）。
     * DRL 可据此选择更保守的"加强"档位。
     */
    private boolean patientJustAdmitted;

    /** 距最近一次有效采样的秒数；-1 表示从未采样 */
    private long secondsSinceLastSample = -1L;
}
