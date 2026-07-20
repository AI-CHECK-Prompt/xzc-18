package com.icu.monitor.protocol.tcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icu.monitor.protocol.DeviceProtocolAdapter;
import com.icu.monitor.protocol.UnifiedMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 厂商私有 TCP 协议适配器（基于 Netty）。
 * 真实场景中不同厂商有不同的二进制帧格式，此处定义统一接口；
 * 新增厂商只需新增一个继承自 DeviceProtocolAdapter 的实现并实现解析逻辑。
 *
 * 帧格式（演示）：4 字节 magic "ICU1" + 4 字节长度（大端） + 长度字节的 JSON
 *   {
 *     "sn":"VENDOR-X-001","patientId":123,
 *     "channel":"SPO2","value":97.0,"quality":99
 *   }
 */
@Component
public class PrivateTcpAdapter implements DeviceProtocolAdapter {

    @Value("${icu.private-tcp-port:9300}")
    private int port = 9300;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicLong bedSerial = new AtomicLong(200);
    private final ConcurrentHashMap<String, Long> snToBed = new ConcurrentHashMap<>();
    private EventLoopGroup boss, worker;
    private ChannelFuture future;

    public PrivateTcpAdapter(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    @Override public String name() { return "PRIVATE_TCP"; }

    @Override
    public void start() throws Exception {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(4);
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast(new FrameDecoder(), new FrameHandler());
             }
         });
        future = b.bind(port).sync();
        System.out.println("[PRIVATE_TCP] listening on " + port);
    }

    @Override
    public void stop() {
        try { if (future != null) future.channel().close().sync(); } catch (Exception ignore) {}
        try { if (boss != null) boss.shutdownGracefully(); } catch (Exception ignore) {}
        try { if (worker != null) worker.shutdownGracefully(); } catch (Exception ignore) {}
    }

    private class FrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, java.util.List<Object> out) {
            while (in.readableBytes() >= 8) {
                in.markReaderIndex();
                int magic = in.readInt();
                if (magic != 0x49435531) { // "ICU1"
                    in.resetReaderIndex();
                    in.skipBytes(in.readableBytes());
                    return;
                }
                int len = in.readInt();
                if (in.readableBytes() < len) { in.resetReaderIndex(); return; }
                byte[] data = new byte[len];
                in.readBytes(data);
                out.add(data);
            }
        }
    }

    private class FrameHandler extends SimpleChannelInboundHandler<byte[]> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] data) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = json.readValue(data, java.util.Map.class);
                String sn = String.valueOf(m.get("sn"));
                long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
                Long patientId = m.get("patientId") == null ? null : Long.valueOf(m.get("patientId").toString());
                String code = String.valueOf(m.get("channel"));
                double v = Double.parseDouble(m.get("value").toString());
                int q = m.get("quality") == null ? 100 : Integer.parseInt(m.get("quality").toString());
                UnifiedMessage u = new UnifiedMessage(
                    OffsetDateTime.now(ZoneId.of("UTC")),
                    "PRIVATE_TCP", bedId, patientId, sn, code, v, null, q, "RAW", null);
                kafka.send("icu.sample.raw", u.getBedId().toString(), json.writeValueAsString(u));
            } catch (Exception e) {
                System.err.println("[PRIVATE_TCP] parse error: " + e.getMessage());
            }
        }
    }

    public void injectMockMessage(String sn, String code, double val) {
        long bedId = snToBed.computeIfAbsent(sn, k -> bedSerial.getAndIncrement());
        UnifiedMessage u = new UnifiedMessage(
            OffsetDateTime.now(ZoneId.of("UTC")),
            "PRIVATE_TCP", bedId, 3000L + bedId, sn, code, val, null, 100, "RAW", null);
        try { kafka.send("icu.sample.raw", u.getBedId().toString(), json.writeValueAsString(u)); } catch (Exception ignore) {}
    }
}
