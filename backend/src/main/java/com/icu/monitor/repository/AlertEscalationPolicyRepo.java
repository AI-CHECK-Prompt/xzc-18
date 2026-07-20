package com.icu.monitor.repository;

import com.icu.monitor.domain.AlertEscalationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertEscalationPolicyRepo extends JpaRepository<AlertEscalationPolicy, Long> {
    List<AlertEscalationPolicy> findByHospitalId(Long hospitalId);
}
