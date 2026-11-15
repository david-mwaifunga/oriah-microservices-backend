package com.oriah.finance.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A refusal is logged by its status and its problem type, never by the message: the message can echo what the person typed, and the log is kept and shipped. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ErrorLogPrivacyTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a refused request is in the log as its status and type, and what was typed is not")
    void whatWasTypedIsNotLogged(CapturedOutput output) throws Exception {
        String typed = "WIDGETZZ" + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(post("/api/v1/admin/finance/expenses").with(jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN")))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"" + typed + "\",\"amount\":10.00,\"expenseDate\":\"2018-04-10\",\"description\":\"rent\"}")).andReturn();

        assertThat(output.getAll()).contains("unknown-category").doesNotContain(typed);
    }
}
