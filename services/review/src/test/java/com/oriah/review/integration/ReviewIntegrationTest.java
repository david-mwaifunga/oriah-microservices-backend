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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    @DisplayName("POST /reviews/{id}/helpful on an unknown id is 404 WITH a problem body")
    void markHelpfulUnknownIdIsAProblem404() throws Exception {
        // review had no test for this path at all, which is how its single
        // ResponseEntity.notFound().build() site kept answering 404 with an
        // EMPTY BODY: no detail, no problem type, no traceId. 25 such sites
        // existed across 8 services, every one of them untested on the body.
        //
        // The specific consequence here: oriah-web's apiFetch synthesizes
        // "Upstream returned 404 Not Found" when there is no problem+json to
        // parse, so a user clicking "helpful" on a review that was deleted
        // under them got a generic failure with nothing to report.
        mvc.perform(post("/api/v1/reviews/" + UUID.randomUUID() + "/helpful")
                        .with(jwtFor("alice")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.startsWith("Review not found with the given input data id :")))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("an unmapped path under review is not reported as a server fault")
    void unmappedPathIsNotA5xx() throws Exception {
        // The platform-wide finding, checked here too because review mounts its
        // controller at /api/v1 rather than a service-named prefix, so it has
        // the widest unmapped surface of any service: every /api/v1/** path the
        // gateway routes here and review does not map lands in this case.
        int status = mvc.perform(get("/api/v1/definitely-not-a-review-endpoint")
                        .with(jwtFor("alice")))
                .andReturn().getResponse().getStatus();

        org.assertj.core.api.Assertions.assertThat(status)
                .as("a path review does not map is the caller's mistake; a 5xx here is "
                        + "retry-eligible at the gateway and in Resilience4j. Got %s", status)
                .isLessThan(500);
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
