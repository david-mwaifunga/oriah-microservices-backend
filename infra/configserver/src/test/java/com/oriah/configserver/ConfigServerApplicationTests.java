package com.oriah.configserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for the Config Server.
 *
 * <p>Boots the full Spring Boot context with {@code @EnableConfigServer}
 * active and verifies the well-known endpoint
 * {@code /{application}/{profile}} returns the YAML we shipped at
 * {@code classpath:/config/}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigServerApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("server boots and serves the catalog default profile")
    void servesCatalogDefaultProfile() throws Exception {
        // /{application}/{profile} — 'default' is the implicit profile.
        mvc.perform(get("/catalog/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("catalog"))
                .andExpect(jsonPath("$.profiles[0]").value("default"))
                .andExpect(jsonPath("$.propertySources").isArray());
    }

    @Test
    @DisplayName("server returns 200 for an unknown application (empty propertySources)")
    void unknownAppReturns200() throws Exception {
        // Config Server returns 200 with no propertySources rather than 404.
        // Clients are expected to handle the absence of config gracefully.
        mvc.perform(get("/unknown-service/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("unknown-service"));
    }
}
