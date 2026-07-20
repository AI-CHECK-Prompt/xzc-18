package com.icu.monitor.repository;

import com.icu.monitor.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface HospitalRepo extends JpaRepository<Hospital, Long> {
    Optional<Hospital> findByCode(String code);
}
