package com.icu.monitor.protocol;

/**
 * 协议适配器插件接口。
 * 接入层：协议适配器 + 统一内部消息总线（Kafka）的双层结构。
 * 新增厂商只需实现此接口，注册到 PluginRegistry 即可。
 */
public interface DeviceProtocolAdapter {
    /** 适配器名称（厂商/协议标识） */
    String name();

    /** 启动（绑定端口/订阅） */
    void start() throws Exception;

    /** 停止 */
    void stop();

    /** 健康检查 */
    default boolean isHealthy() { return true; }
}
