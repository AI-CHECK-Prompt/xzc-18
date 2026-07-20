package com.icu.monitor.repository;

import com.icu.monitor.domain.PlaybackSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaybackSessionRepo extends JpaRepository<PlaybackSession, Long> {
    List<PlaybackSession> findByBedIdOrderByStartAtDesc(Long bedId);
}
