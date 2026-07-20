package com.icu.monitor.scoring;

/** 兼容 SQL 中 import 的别名类 */
public class MEWSResult extends ScoreResult {
    public MEWSResult(double score, String level) { super(score, level); setRuleCode("MEWS"); }
}
