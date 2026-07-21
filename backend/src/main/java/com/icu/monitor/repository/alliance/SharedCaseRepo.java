package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.SharedCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface SharedCaseRepo extends JpaRepository<SharedCase, Long> {
    @Query("select s from SharedCase s where s.allianceId=?1 and s.drgCode=?2 order by s.admissionAt desc")
    List<SharedCase> findByDrg(Long allianceId, String drgCode);

    @Query("select count(s) from SharedCase s where s.allianceId=?1")
    long countByAlliance(Long allianceId);
}
