package com.oriah.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns Spring's scheduling on for this service. Each scheduled job has a switch of its own (the message dispatcher:
 * oriah.notification.send.enabled) and a scheduling thread of its own (spring.task.scheduling.pool.size).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
