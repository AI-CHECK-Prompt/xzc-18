package com.icu.monitor.repository;

import com.icu.monitor.domain.MonitorDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitorDeviceRepo extends JpaRepository<MonitorDevice, Long> {
    List<MonitorDevice> findByBedId(Long bedId);
    Optional<MonitorDevice> findBySerialNo(String serialNo);
}
