package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.JointReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JointReportRepo extends JpaRepository<JointReport, Long> {
    List<JointReport> findByAllianceIdOrderByGeneratedAtDesc(Long allianceId);
    Optional<JointReport> findByAllianceIdAndPeriodQuarter(Long allianceId, String quarter);
}
