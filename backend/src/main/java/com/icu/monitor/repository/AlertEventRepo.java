package com.icu.monitor.repository;

import com.icu.monitor.domain.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface AlertEventRepo extends JpaRepository<AlertEvent, Long> {
    @Query("select a from AlertEvent a where a.bedId = ?1 and a.time between ?2 and ?3 order by a.time desc")
    List<AlertEvent> findByBedAndTimeRange(Long bedId, OffsetDateTime from, OffsetDateTime to);
    @Query("select a from AlertEvent a where a.status = 'OPEN' order by a.time desc")
    List<AlertEvent> findOpen();
    @Query("select a from AlertEvent a where a.bedId=?1 and a.alertType=?2 and a.time > ?3 order by a.time desc")
    List<AlertEvent> findRecentSame(Long bedId, String alertType, OffsetDateTime since);
}
