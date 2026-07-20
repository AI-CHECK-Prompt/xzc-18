package com.icu.monitor.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {
    @Value("${icu.alert.topics.raw}")    private String rawTopic;
    @Value("${icu.alert.topics.metric}") private String metricTopic;
    @Value("${icu.alert.topics.alert}")  private String alertTopic;

    @Bean public NewTopic rawTopic()    { return new NewTopic(rawTopic,    6, (short)1); }
    @Bean public NewTopic metricTopic() { return new NewTopic(metricTopic, 6, (short)1); }
    @Bean public NewTopic alertTopic()  { return new NewTopic(alertTopic,  3, (short)1); }
}
