package com.icu.monitor.repository;

import com.icu.monitor.domain.NursingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface NursingRecordRepo extends JpaRepository<NursingRecord, Long> {
    @Query("select n from NursingRecord n where n.bedId=?1 and n.time between ?2 and ?3 order by n.time")
    List<NursingRecord> findInRange(Long bedId, OffsetDateTime from, OffsetDateTime to);
}
