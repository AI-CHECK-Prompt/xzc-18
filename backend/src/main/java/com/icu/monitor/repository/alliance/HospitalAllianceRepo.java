package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.HospitalAlliance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HospitalAllianceRepo extends JpaRepository<HospitalAlliance, Long> {
    Optional<HospitalAlliance> findByCode(String code);
}
