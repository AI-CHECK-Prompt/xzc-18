package com.icu.monitor.repository;

import com.icu.monitor.domain.ScoringRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScoringRuleRepo extends JpaRepository<ScoringRule, Long> {
    List<ScoringRule> findByHospitalIdAndEnabledTrue(Long hospitalId);
    Optional<ScoringRule> findByHospitalIdAndCodeAndVersion(Long hospitalId, String code, Integer version);
}
