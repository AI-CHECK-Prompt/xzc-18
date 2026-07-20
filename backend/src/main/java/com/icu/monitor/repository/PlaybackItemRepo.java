package com.icu.monitor.repository;

import com.icu.monitor.domain.PlaybackItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlaybackItemRepo extends JpaRepository<PlaybackItem, Long> {
    @Query("select p from PlaybackItem p where p.sessionId=?1 order by p.time")
    List<PlaybackItem> findBySession(Long sessionId);
}
