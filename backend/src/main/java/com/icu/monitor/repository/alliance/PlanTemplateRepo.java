package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.PlanTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlanTemplateRepo extends JpaRepository<PlanTemplate, Long> {
    List<PlanTemplate> findByAllianceIdAndDrgCodeOrderByEvidenceLevelAsc(Long allianceId, String drgCode);
    List<PlanTemplate> findByAllianceId(Long allianceId);
}
