package com.icu.monitor.repository;

import com.icu.monitor.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PatientRepo extends JpaRepository<Patient, Long> {
    Optional<Patient> findByMrn(String mrn);
}
