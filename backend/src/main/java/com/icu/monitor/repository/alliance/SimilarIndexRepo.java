package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.SimilarIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SimilarIndexRepo extends JpaRepository<SimilarIndex, Long> {
    List<SimilarIndex> findBySharedCaseIdIn(List<Long> ids);
}
