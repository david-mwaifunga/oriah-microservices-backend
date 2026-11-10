package com.oriah.promotion.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.oriah.promotion.TestcontainersConfiguration;
import com.oriah.promotion.repository.PromotionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating a second promotion with a code that already exists is the caller's
 * conflict to fix: 409, in the platform's problem shape.
 *
 * <p>It used to be a 500. The {@code code} column is unique and the service
 * checked nothing before inserting, so the database refused the row and the
 * failure came out as an unexplained server error, with a stack trace in the log
 * for what is an ordinary admin typo. Codes are stored in upper case (the model
 * does it), so "welcome15" collides with "WELCOME15" and must be refused the same
 * way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class DuplicateCodeTest {

    private static final SimpleGrantedAuthority ROLE_CATALOG_ADMIN = new SimpleGrantedAuthority("ROLE_CATALOG_ADMIN");
    private static final String PATH = "/api/v1/admin/promotions";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PromotionRepository repository;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private ResultActions create(String code, int amount) throws Exception {
        return mvc.perform(post(PATH).with(jwt().authorities(ROLE_CATALOG_ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"type\":\"FIXED\",\"amount\":" + amount + "}"));
    }

    @Test
    @DisplayName("the same code twice is 409 conflict, and the first promotion is untouched")
    void aDuplicateCodeIsA409() throws Exception {
        create("WELCOME15", 15).andExpect(status().isCreated());

        create("WELCOME15", 99)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/conflict"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByCode("WELCOME15")).get()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("15"));
    }

    @Test
    @DisplayName("a code in another letter case is the same code: 409")
    void aDifferentLetterCaseIsTheSameCode() throws Exception {
        create("WELCOME15", 15).andExpect(status().isCreated());

        create("welcome15", 15).andExpect(status().isConflict());
        create("Welcome15", 15).andExpect(status().isConflict());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the 409 does not leak the database's own message or the constraint's name")
    void theConflictDoesNotLeakTheDriverMessage() throws Exception {
        create("WELCOME15", 15).andExpect(status().isCreated());

        create("WELCOME15", 15)
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("promotions_code_key"))))
                .andExpect(content().string(not(containsString("duplicate key"))))
                .andExpect(content().string(not(containsString("23505"))));
    }

    @Test
    @DisplayName("a different code is still created: the 409 is only for a code that exists")
    void aDifferentCodeIsStillCreated() throws Exception {
        create("WELCOME15", 15).andExpect(status().isCreated());

        create("SPRING20", 20).andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
    }
}
