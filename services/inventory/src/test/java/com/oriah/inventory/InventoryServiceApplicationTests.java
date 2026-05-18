package com.oriah.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class InventoryServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
