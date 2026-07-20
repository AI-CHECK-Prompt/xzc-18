package com.icu.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class IcuMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(IcuMonitorApplication.class, args);
    }
}
