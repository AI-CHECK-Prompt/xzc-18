package com.icu.monitor.scoring;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/** Drools 规则计算产出的评分结果 fact */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreResult {
    private String ruleCode;
    private double score;
    private String level;            // NORMAL / WARN / CRITICAL
    public ScoreResult(double score, String level) { this.score = score; this.level = level; }
}
