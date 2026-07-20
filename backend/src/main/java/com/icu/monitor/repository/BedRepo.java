package com.icu.monitor.repository;

import com.icu.monitor.domain.Bed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BedRepo extends JpaRepository<Bed, Long> {
    List<Bed> findByWardId(Long wardId);
    @Query("select b from Bed b where b.patientId is not null")
    List<Bed> findOccupied();
}
