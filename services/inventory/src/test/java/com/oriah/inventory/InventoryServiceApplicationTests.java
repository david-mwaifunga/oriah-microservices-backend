package com.oriah.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class InventoryServiceApplicationTests {

    /**
     * OAuth2 resource server tries to fetch JWKS at startup; replace with
     * a mock so the context boots without a live Keycloak.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}
