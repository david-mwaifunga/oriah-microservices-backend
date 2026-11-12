package com.oriah.procurement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// Registers com.oriah.procurement.dto.ProcurementContactInfoDto. Matches
// catalog's precedent rather than the course's @EnableConfigurationProperties(X.class),
// which has to be re-edited for every future properties class.
@ConfigurationPropertiesScan
// The one outbound client: catalog's variant lookup (com.oriah.procurement.client).
@EnableFeignClients
@Slf4j
public class ProcurementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProcurementServiceApplication.class, args);
        log.info("procurement-service ready");
    }
}
