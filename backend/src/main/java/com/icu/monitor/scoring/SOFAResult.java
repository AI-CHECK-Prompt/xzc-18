package com.icu.monitor.scoring;

public class SOFAResult extends ScoreResult {
    public SOFAResult(double score, String level) { super(score, level); setRuleCode("SOFA"); }
}
