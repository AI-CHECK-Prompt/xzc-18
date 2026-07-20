package com.icu.monitor.repository;

import com.icu.monitor.domain.DeviceChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceChannelRepo extends JpaRepository<DeviceChannel, Long> {
    List<DeviceChannel> findByDeviceId(Long deviceId);
    Optional<DeviceChannel> findByDeviceIdAndCode(Long deviceId, String code);
}
