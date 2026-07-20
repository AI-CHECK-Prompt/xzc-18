package com.icu.monitor.repository;

import com.icu.monitor.domain.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WardRepo extends JpaRepository<Ward, Long> {
    List<Ward> findByHospitalId(Long hospitalId);
}
