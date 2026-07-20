package com.icu.monitor.controller;

import com.icu.monitor.domain.PlaybackItem;
import com.icu.monitor.domain.PlaybackSession;
import com.icu.monitor.playback.PlaybackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/playback")
@CrossOrigin(origins = "*")
public class PlaybackController {

    @Autowired private PlaybackService service;

    @GetMapping("/by-bed/{bedId}")
    public List<PlaybackSession> byBed(@PathVariable Long bedId) { return service.byBed(bedId); }

    @GetMapping("/{sessionId}/items")
    public List<PlaybackItem> items(@PathVariable Long sessionId) { return service.items(sessionId); }

    /** 手工建立抢救回放 */
    @PostMapping("/manual")
    public PlaybackSession manual(@RequestParam Long bedId,
                                  @RequestParam(required = false) Long patientId,
                                  @RequestParam(required = false) String centerAt) {
        OffsetDateTime c = centerAt == null ? OffsetDateTime.now() : OffsetDateTime.parse(centerAt);
        return service.createManual(bedId, patientId, c);
    }
}
