package com.oriah.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// Registers com.oriah.notification.dto.NotificationContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
        log.info("notification-service ready");
    }
}
