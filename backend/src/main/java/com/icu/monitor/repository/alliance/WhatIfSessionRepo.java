package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.WhatIfSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WhatIfSessionRepo extends JpaRepository<WhatIfSession, Long> {
    List<WhatIfSession> findByAllianceIdOrderByCreatedAtDesc(Long allianceId);
    List<WhatIfSession> findBySourcePatientIdOrderByCreatedAtDesc(Long sourcePatientId);
}
