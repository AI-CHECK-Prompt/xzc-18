package com.icu.monitor.protocol.hl7;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v26.message.ORU_R01;
import ca.uhn.hl7v2.model.v26.segment.OBX;
import ca.uhn.hl7v2.model.v26.segment.PID;
import ca.uhn.hl7v2.parser.PipeParser;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HL7 v2.x ORU 波形消息适配器。
 * 监听 TCP 端口，接收 MLLP 帧（VT + 内容 + FS+CR），解析后产出 UnifiedMessage 并投递到 Kafka。
 * 适配迈瑞/飞利浦/GE 中支持 HL7 v2 的型号。
 */
@Component
public class Hl7V2Adapter implements DeviceProtocolAdapter {

    @Value("${icu.hl7-adapter-port:9200}")
    private int port;
    private final KafkaTemplate<String, String> kafka;
    private final PipeParser parser = new PipeParser();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicLong bedSerial = new AtomicLong(1);
    private final ConcurrentHashMap<String, Long> snToBed = new ConcurrentHashMap<>();
    private ServerSocket server;

    public Hl7V2Adapter(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    @Override public String name() { return "HL7_V2"; }

    @Override
    public void start() throws Exception {
        server = new ServerSocket(port);
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s), "hl7v2-client").start();
                } catch (Exception e) {
                    if (!server.isClosed()) e.printStackTrace();
                }
            }
        }, "hl7v2-accept");
        accept.setDaemon(true);
        accept.start();
    }

    @Override public void stop() { try { if (server != null) server.close(); } catch (Exception ignore) {} }

    private void handle(Socket sock) {
        try {
            java.io.InputStream in = sock.getInputStream();
            StringBuilder buf = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == 0x0B) continue;            // VT
                if (b == 0x1C) {                    // FS
                    parseAndPublish(buf.toString());
                    buf.setLength(0);
                    sock.getOutputStream().write(0x1C);
                    sock.getOutputStream().write(0x0D);
                } else {
                    buf.append((char) b);
                }
            }
        } catch (Exception ignored) {}
    }

    private void parseAndPublish(String raw) {
        try {
            Message msg = parser.parse(raw);
            if (!(msg instanceof ORU_R01)) return;
            ORU_R01 oru = (ORU_R01) msg;
            String sn = oru.getMSH().getSendingApplication().getNamespaceID().getValue();
            long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
            Long patientId = null;
            try {
                PID pid = oru.getPATIENT_RESULT().getPATIENT().getPID();
                if (pid != null && pid.getPatientIdentifierList().length > 0) {
                    patientId = Long.parseLong(pid.getPatientIdentifierList(0).getIDNumber().getValue());
                }
            } catch (Exception ignore) {}
            for (int i = 0; i < oru.getPATIENT_RESULT().getORDER_OBSERVATION().getOBSERVATIONReps(); i++) {
                var obs = oru.getPATIENT_RESULT().getORDER_OBSERVATION().getOBSERVATION(i);
                for (int j = 0; j < obs.getOBSERVATIONReps(); j++) {
                    OBX obx = obs.getOBX(j);
                    String code = obx.getObservationIdentifier().getText().getValue();
                    String val = obx.getObservationValue(0).getData();
                    Double v;
                    try { v = Double.parseDouble(val); } catch (Exception ex) { continue; }
                    UnifiedMessage u = new UnifiedMessage(
                        OffsetDateTime.now(ZoneId.of("UTC")),
                        "HL7_V2", bedId, patientId, sn, code, v, null, 100, "RAW", null);
                    publish(u);
                }
            }
        } catch (Exception e) {
            System.err.println("[HL7_V2] parse error: " + e.getMessage());
        }
    }

    private void publish(UnifiedMessage u) {
        try {
            kafka.send("icu.sample.raw", u.getBedId().toString(), json.writeValueAsString(u));
        } catch (Exception ignore) {}
    }

    /** 模拟供自检使用 */
    public void injectMockMessage(String sn, String code, double val) {
        long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
        UnifiedMessage u = new UnifiedMessage(
            OffsetDateTime.now(ZoneId.of("UTC")),
            "HL7_V2", bedId, 1000L + bedId, sn, code, val, null, 100, "RAW", null);
        publish(u);
    }
}
