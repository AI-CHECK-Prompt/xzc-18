package com.icu.monitor.protocol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议插件注册中心。
 * 自动发现所有 DeviceProtocolAdapter Bean，启动后注册。
 */
@Component
public class PluginRegistry {
    @Autowired private List<DeviceProtocolAdapter> adapters;
    private final Map<String, DeviceProtocolAdapter> map = new HashMap<>();

    @PostConstruct
    public void init() throws Exception {
        for (DeviceProtocolAdapter a : adapters) {
            map.put(a.name(), a);
            a.start();
            System.out.println("[PluginRegistry] adapter started: " + a.name());
        }
    }

    public DeviceProtocolAdapter get(String name) { return map.get(name); }
    public Map<String, DeviceProtocolAdapter> all() { return map; }
}
