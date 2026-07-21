package com.icu.monitor.controller;

import com.icu.monitor.domain.*;
import com.icu.monitor.protocol.UnifiedMessage;
import com.icu.monitor.protocol.hl7.Hl7V2Adapter;
import com.icu.monitor.protocol.ihe.IhePcdAdapter;
import com.icu.monitor.protocol.tcp.PrivateTcpAdapter;
import com.icu.monitor.protocol.PluginRegistry;
import com.icu.monitor.repository.AlertEscalationPolicyRepo;
import com.icu.monitor.repository.AlertEventRepo;
import com.icu.monitor.repository.BedRepo;
import com.icu.monitor.repository.MonitorDeviceRepo;
import com.icu.monitor.repository.OrderExecutionRepo;
import com.icu.monitor.repository.PatientRepo;
import com.icu.monitor.repository.ScoringRuleRepo;
import com.icu.monitor.repository.TimeSeriesDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired private BedRepo bedRepo;
    @Autowired private PatientRepo patientRepo;
    @Autowired private MonitorDeviceRepo deviceRepo;
    @Autowired private AlertEventRepo alertRepo;
    @Autowired private AlertEscalationPolicyRepo policyRepo;
    @Autowired private ScoringRuleRepo scoringRuleRepo;
    @Autowired private OrderExecutionRepo orderRepo;
    @Autowired private TimeSeriesDao tsDao;
    @Autowired private PluginRegistry pluginRegistry;
    @Autowired private Hl7V2Adapter hl7V2Adapter;
    @Autowired private IhePcdAdapter ihePcdAdapter;
    @Autowired private PrivateTcpAdapter privateTcpAdapter;
    @Autowired private com.icu.monitor.scoring.ScoringEngine scoringEngine;
    @Autowired private com.icu.monitor.push.NurseStationPusher pusher;

    /** 1) 多床位总览看板 */
    @GetMapping("/beds")
    public List<Map<String, Object>> beds() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Bed b : bedRepo.findAll()) {
            Map<String, Object> m = new HashMap<>();
            m.put("bedId", b.getId());
            m.put("code", b.getCode());
            m.put("status", b.getStatus());
            m.put("patientId", b.getPatientId());
            // 最新告警
            var open = alertRepo.findOpen();
            AlertEvent latest = null;
            for (AlertEvent a : open) if (a.getBedId().equals(b.getId())) { latest = a; break; }
            m.put("latestAlert", latest);
            out.add(m);
        }
        return out;
    }

    /** 2) 单床位详情 */
    @GetMapping("/bed/{bedId}")
    public Map<String, Object> bed(@PathVariable Long bedId) {
        Map<String, Object> r = new HashMap<>();
        Bed b = bedRepo.findById(bedId).orElse(null);
        r.put("bed", b);
        if (b != null && b.getPatientId() != null) {
            r.put("patient", patientRepo.findById(b.getPatientId()).orElse(null));
        }
        r.put("devices", deviceRepo.findByBedId(bedId));
        return r;
    }

    /** 3) 实时波形数据（衍生指标） */
    @GetMapping("/metric")
    public List<Map<String, Object>> metric(@RequestParam Long bedId,
                                            @RequestParam(defaultValue = "60") int minutes) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusMinutes(minutes);
        return tsDao.queryMetricWindow(bedId, 0L, from, to);
    }

    /** 4) 原始波形 */
    @GetMapping("/waveform")
    public List<Map<String, Object>> waveform(@RequestParam Long bedId,
                                              @RequestParam(defaultValue = "200") int limit) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusMinutes(5);
        return tsDao.queryRawWindow(bedId, 0L, from, to, limit);
    }

    /** 5) 告警事件 */
    @GetMapping("/alerts")
    public List<AlertEvent> alerts(@RequestParam(required = false) Long bedId,
                                   @RequestParam(defaultValue = "100") int limit) {
        if (bedId != null) {
            return alertRepo.findByBedAndTimeRange(bedId,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusMinutes(1));
        }
        return alertRepo.findOpen();
    }

    /** 6) 告警确认/关闭 */
    @PostMapping("/alert/{id}/ack")
    public AlertEvent ack(@PathVariable Long id) {
        AlertEvent a = alertRepo.findById(id).orElseThrow();
        a.setStatus("ACK");
        return alertRepo.save(a);
    }

    /** 7) 评分规则管理 */
    @GetMapping("/scoring/rule")
    public List<ScoringRule> listRules() { return scoringRuleRepo.findAll(); }

    @PostMapping("/scoring/rule")
    public ScoringRule saveRule(@RequestBody ScoringRule r) {
        r.setCreatedAt(OffsetDateTime.now());
        ScoringRule saved = scoringRuleRepo.save(r);
        scoringEngine.reload(saved);
        return saved;
    }

    /** 8) 评分结果查询 */
    @GetMapping("/scoring/result")
    public List<Map<String, Object>> scoreResult(@RequestParam Long patientId,
                                                 @RequestParam String ruleCode) {
        return tsDao.recentScoring(patientId, ruleCode, 20);
    }

    /** 9) 升级策略 */
    @GetMapping("/alert/policy")
    public List<AlertEscalationPolicy> policies() { return policyRepo.findAll(); }

    /** 10) HIS 同步：患者档案 / 医嘱 */
    @PostMapping("/his/patient")
    public Patient hisPatient(@RequestBody(required = false) Patient p,
                              @RequestParam(required = false) Long hospitalId,
                              @RequestParam(required = false) String mrn,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String gender,
                              @RequestParam(required = false) String birthDate,
                              @RequestParam(required = false) String diagnosis) {
        if (p == null) p = new Patient();
        p.setCreatedAt(OffsetDateTime.now());
        if (p.getNameMask() == null) p.setNameMask(name == null ? "张*" : name);
        if (p.getNameEnc() == null) p.setNameEnc(new byte[]{0});
        if (p.getHospitalId() == null && hospitalId != null) p.setHospitalId(hospitalId);
        if (p.getMrn() == null && mrn != null) p.setMrn(mrn);
        if (p.getGender() == null && gender != null) p.setGender(gender);
        if (p.getBirthDate() == null && birthDate != null) p.setBirthDate(java.time.LocalDate.parse(birthDate));
        if (p.getDiagnosis() == null && diagnosis != null) p.setDiagnosis(diagnosis);
        return patientRepo.save(p);
    }

    @PostMapping("/his/order")
    public OrderExecution hisOrder(@RequestBody OrderExecution o) {
        o.setCreatedAt(OffsetDateTime.now());
        return orderRepo.save(o);
    }

    /** 11) 床位绑定患者 */
    @PostMapping("/bed/{bedId}/bind")
    public Bed bindPatient(@PathVariable Long bedId, @RequestParam Long patientId) {
        Bed b = bedRepo.findById(bedId).orElseThrow();
        b.setPatientId(patientId);
        b.setStatus("OCCUPIED");
        return bedRepo.save(b);
    }

    /** 12) 自检 - 模拟一次生命体征写入 */
    @PostMapping("/selfcheck/inject")
    public Map<String, Object> inject(@RequestParam String protocol,
                                      @RequestParam String sn,
                                      @RequestParam String channel,
                                      @RequestParam double value,
                                      @RequestParam(required = false) Long patientId) {
        switch (protocol.toUpperCase()) {
            case "HL7_V2":     hl7V2Adapter.injectMockMessage(sn, channel, value, patientId); break;
            case "IHE_PCD":    ihePcdAdapter.injectMockMessage(sn, channel, value, patientId); break;
            case "PRIVATE_TCP":privateTcpAdapter.injectMockMessage(sn, channel, value, patientId); break;
            default: throw new IllegalArgumentException("unknown protocol: " + protocol);
        }
        return Map.of("ok", true, "protocol", protocol, "channel", channel, "value", value, "patientId", patientId == null ? -1L : patientId);
    }

    /** 13) 设备管理 */
    @GetMapping("/devices")
    public List<MonitorDevice> devices() { return deviceRepo.findAll(); }

    @PostMapping("/device")
    public MonitorDevice saveDevice(@RequestBody MonitorDevice d) {
        return deviceRepo.save(d);
    }

    /** 14) WebSocket session 数量（监控护士站在线数） */
    @GetMapping("/push/online")
    public Map<String, Object> online() { return Map.of("sessions", pusher.sessionCount()); }

    /** 15) 协议插件 */
    @GetMapping("/plugins")
    public Map<String, Object> plugins() {
        Map<String, Object> m = new HashMap<>();
        pluginRegistry.all().forEach((k, v) -> m.put(k, Map.of("name", v.name(), "healthy", v.isHealthy())));
        return m;
    }
}
