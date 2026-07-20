package com.icu.monitor.protocol.ihe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icu.monitor.protocol.DeviceProtocolAdapter;
import com.icu.monitor.protocol.UnifiedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IHE PCD (Patient Care Device) 框架适配器。
 * 实际 IHE PCD 通常基于 HL7 v2 + ACM/BEC 消息；此处提供简化的 HTTP 接入端点，
 * 接收符合 IHE PCD DEC (Device Enterprise Communication) 风格的 JSON 消息，
 * 内部转换为 UnifiedMessage。
 */
@Component
public class IhePcdAdapter implements DeviceProtocolAdapter {

    @Value("${icu.tcp-adapter-port:9100}")
    private int port;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicLong bedSerial = new AtomicLong(100);
    private final ConcurrentHashMap<String, Long> snToBed = new ConcurrentHashMap<>();
    private ServerSocket server;

    public IhePcdAdapter(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    @Override public String name() { return "IHE_PCD"; }

    @Override
    public void start() throws Exception {
        server = new ServerSocket(port);
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s), "ihe-client").start();
                } catch (Exception e) {
                    if (!server.isClosed()) e.printStackTrace();
                }
            }
        }, "ihe-accept");
        accept.setDaemon(true);
        accept.start();
    }

    @Override public void stop() { try { if (server != null) server.close(); } catch (Exception ignore) {} }

    /**
     * 简化协议：每行为一条 JSON，形如
     *  {"deviceSn":"PCD-001","patientId":42,"channel":"HR","value":78.0,"quality":98}
     * 行结束符 \n。生产可改为 IHE PCD DEC 的 PCD-01 / ORU 消息。
     */
    private void handle(Socket s) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = json.readValue(line, Map.class);
                    String sn = String.valueOf(m.get("deviceSn"));
                    long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
                    Long patientId = m.get("patientId") == null ? null : Long.valueOf(m.get("patientId").toString());
                    String code = String.valueOf(m.get("channel"));
                    double v = Double.parseDouble(m.get("value").toString());
                    int q = m.get("quality") == null ? 100 : Integer.parseInt(m.get("quality").toString());
                    UnifiedMessage u = new UnifiedMessage(
                        OffsetDateTime.now(ZoneId.of("UTC")),
                        "IHE_PCD", bedId, patientId, sn, code, v, null, q, "RAW", null);
                    publish(u);
                } catch (Exception ex) {
                    System.err.println("[IHE_PCD] parse error: " + ex.getMessage());
                }
            }
        } catch (Exception ignored) {}
    }

    private void publish(UnifiedMessage u) {
        try {
            kafka.send("icu.sample.raw", u.getBedId().toString(), json.writeValueAsString(u));
        } catch (Exception ignore) {}
    }

    public void injectMockMessage(String sn, String code, double val) {
        long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
        UnifiedMessage u = new UnifiedMessage(
            OffsetDateTime.now(ZoneId.of("UTC")),
            "IHE_PCD", bedId, 2000L + bedId, sn, code, val, null, 100, "RAW", null);
        publish(u);
    }
}
