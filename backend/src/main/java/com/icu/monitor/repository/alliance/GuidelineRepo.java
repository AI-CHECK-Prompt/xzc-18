package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.Guideline;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GuidelineRepo extends JpaRepository<Guideline, Long> {
    List<Guideline> findByDrgCode(String drgCode);
    List<Guideline> findByMdcCode(String mdcCode);
}
