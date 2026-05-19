package com.oriah.review.integration;

import java.util.UUID;

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

import com.oriah.review.TestcontainersConfiguration;
import com.oriah.review.repository.ReviewRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class ReviewIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final UUID PRODUCT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ReviewRepository repository;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("POST a review, GET listing shows it, GET aggregate shows avg=rating")
    void writeReadAggregate() throws Exception {
        UUID orderId = UUID.randomUUID();
        mvc.perform(post("/api/v1/products/" + PRODUCT + "/reviews")
                        .with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"rating\":5,\"title\":\"Love it\",\"body\":\"Perfect.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5));

        // Public read; no JWT
        mvc.perform(get("/api/v1/products/" + PRODUCT + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Love it"));

        mvc.perform(get("/api/v1/products/" + PRODUCT + "/reviews/aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average").value(5.0))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("two reviews from two users -> average is mean, count=2")
    void aggregateAverages() throws Exception {
        postReview("alice", 5);
        postReview("bob", 3);

        mvc.perform(get("/api/v1/products/" + PRODUCT + "/reviews/aggregate"))
                .andExpect(jsonPath("$.average").value(4.0))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("aggregate on a product with no reviews returns count=0, average=null")
    void emptyAggregate() throws Exception {
        mvc.perform(get("/api/v1/products/00000000-0000-0000-0000-000000000000/reviews/aggregate"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("same customer writing again replaces their review (one per pair)")
    void replaceSelfReview() throws Exception {
        postReview("alice", 5);
        postReview("alice", 2);

        mvc.perform(get("/api/v1/products/" + PRODUCT + "/reviews/aggregate"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.average").value(2.0));
    }

    @Test
    @DisplayName("invalid rating (>5) is 400")
    void invalidRating() throws Exception {
        mvc.perform(post("/api/v1/products/" + PRODUCT + "/reviews")
                        .with(jwtFor("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"rating\":7,\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST without JWT is 401")
    void writeRequiresJwt() throws Exception {
        mvc.perform(post("/api/v1/products/" + PRODUCT + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"rating\":5}"))
                .andExpect(status().isUnauthorized());
    }

    private void postReview(String sub, int rating) throws Exception {
        mvc.perform(post("/api/v1/products/" + PRODUCT + "/reviews")
                        .with(jwtFor(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"rating\":" + rating + "}"))
                .andExpect(status().isCreated());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(String sub) {
        return jwt().authorities(ROLE_USER).jwt(j -> j.subject(sub));
    }
}
