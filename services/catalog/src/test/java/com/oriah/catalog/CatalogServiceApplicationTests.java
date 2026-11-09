package com.oriah.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@SpringBootTest
@TestPropertySource(properties = {
        // Disable Eureka in tests so they don't hang trying to register
        // with a Eureka server that isn't running.
        "eureka.client.enabled=false"
})
class CatalogServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
