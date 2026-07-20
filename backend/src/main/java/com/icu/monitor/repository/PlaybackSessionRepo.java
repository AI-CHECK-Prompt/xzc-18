package com.icu.monitor.repository;

import com.icu.monitor.domain.PlaybackSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaybackSessionRepo extends JpaRepository<PlaybackSession, Long> {
    List<PlaybackSession> findByBedIdOrderByStartAtDesc(Long bedId);
    // 幂等：同一 (床位, 触发告警) 在指定状态下最多 1 条回放会话
    Optional<PlaybackSession> findFirstByBedIdAndTriggerAlertIdAndStatus(Long bedId, Long triggerAlertId, String status);
}
