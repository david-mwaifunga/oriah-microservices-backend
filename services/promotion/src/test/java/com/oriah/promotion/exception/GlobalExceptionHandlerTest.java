package com.oriah.promotion.exception;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only one database failure is a conflict the caller can fix: a second promotion
 * with a code that already exists. Every other integrity failure is OUR bug (a
 * missing value, a check the code should have caught first) and must stay a 500,
 * loud, rather than be dressed up as the caller's mistake.
 *
 * <p>The case is recognised by the PostgreSQL error code (23505, unique violation)
 * <em>and</em> the name of the constraint, {@code promotions_code_key}: the name
 * PostgreSQL gave the {@code UNIQUE} on {@code code} in V1. Both must match, so a
 * unique violation on some other column added later is not silently reported as a
 * duplicate code.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException violation(String sqlState, String constraint) {
        SQLException driver = new SQLException("ERROR: violation of " + constraint, sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraint));
    }

    @Test
    @DisplayName("a unique violation on the code is a 409 conflict, with a generic detail")
    void aDuplicateCodeIsAConflict() {
        ResponseEntity<ProblemDetail> response = handler.dataIntegrityViolation(violation("23505", "promotions_code_key"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType().toString()).isEqualTo("https://oriah.dev/problems/conflict");
        assertThat(problem.getDetail()).containsIgnoringCase("code")
                .doesNotContain("promotions_code_key")
                .doesNotContain("duplicate")
                .doesNotContain("23505");
    }

    @Test
    @DisplayName("a unique violation on any other constraint is not this service's conflict: 500")
    void anotherUniqueConstraintStaysA500() {
        assertThat(status(violation("23505", "uq_something_else"))).isEqualTo(500);
    }

    @Test
    @DisplayName("a check, not-null or foreign-key failure is a bug in the code: 500, never 409")
    void otherIntegrityFailuresStayA500() {
        assertThat(status(violation("23514", "ck_promotions_amount"))).isEqualTo(500);
        assertThat(status(violation("23502", "code"))).isEqualTo(500);
        assertThat(status(violation("23503", "fk_anything"))).isEqualTo(500);
    }

    @Test
    @DisplayName("a violation with no constraint information at all is a 500")
    void noInformationIsA500() {
        assertThat(status(new DataIntegrityViolationException("something"))).isEqualTo(500);
    }

    @Test
    @DisplayName("the constraint name alone, with a different error code, is still a 500: both must match")
    void theNameAloneIsNotEnough() {
        assertThat(status(violation("23514", "promotions_code_key"))).isEqualTo(500);
    }

    @Test
    @DisplayName("the 500 is the platform's generic one: no driver message, no constraint name")
    void theFiveHundredIsGeneric() {
        ProblemDetail problem = handler.dataIntegrityViolation(violation("23503", "fk_secret_table")).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getType().toString()).isEqualTo("https://oriah.dev/problems/internal-server-error");
        assertThat(String.valueOf(problem.getDetail())).doesNotContain("fk_secret_table");
    }

    private int status(DataIntegrityViolationException e) {
        return handler.dataIntegrityViolation(e).getStatusCode().value();
    }
}
