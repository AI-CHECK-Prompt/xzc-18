package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.QcMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QcMetricRepo extends JpaRepository<QcMetric, Long> {
    List<QcMetric> findByAllianceIdAndDrgCodeAndPeriodQuarter(Long allianceId, String drgCode, String quarter);
    List<QcMetric> findByAllianceIdAndPeriodQuarter(Long allianceId, String quarter);
}
