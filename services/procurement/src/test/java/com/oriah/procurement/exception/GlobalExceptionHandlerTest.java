package com.oriah.procurement.exception;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only one database failure is a conflict the caller can fix: two live suppliers
 * with the same name. Every other integrity failure is OUR bug (a missing value,
 * a broken foreign key, a check the code should have caught first) and must stay
 * a 500, loud, rather than be dressed up as the caller's mistake.
 *
 * <p>Catalog's handler turns every {@code DataIntegrityViolationException} into a
 * 409. That is too wide for a service that will grow foreign keys in Phase 4: a
 * purchase order pointing at nothing would answer "conflict" instead of paging
 * someone. So this handler recognises the one case by its PostgreSQL error code
 * (23505, unique violation) AND the name of the index, and hands every other
 * violation to the shared catch-all, which logs it with its stack trace and
 * answers the platform's generic 500.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException violation(String sqlState, String constraint) {
        SQLException driver = new SQLException("ERROR: violation of " + constraint, sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraint));
    }

    @Test
    @DisplayName("a unique violation on the live-name index is a 409 conflict, with a generic detail")
    void theLiveNameIndexIsAConflict() {
        ResponseEntity<ProblemDetail> response = handler.dataIntegrityViolation(violation("23505", "uq_suppliers_name_live"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType().toString()).isEqualTo("https://oriah.dev/problems/conflict");
        assertThat(problem.getDetail()).containsIgnoringCase("name")
                .doesNotContain("uq_suppliers_name_live")
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
        assertThat(status(violation("23514", "ck_suppliers_country"))).isEqualTo(500);
        assertThat(status(violation("23502", "name"))).isEqualTo(500);
        assertThat(status(violation("23503", "fk_anything"))).isEqualTo(500);
    }

    @Test
    @DisplayName("a violation with no constraint information at all is a 500")
    void noInformationIsA500() {
        assertThat(status(new DataIntegrityViolationException("something"))).isEqualTo(500);
    }

    @Test
    @DisplayName("the index name alone, with a different error code, is still a 500: both must match")
    void theNameAloneIsNotEnough() {
        assertThat(status(violation("23514", "uq_suppliers_name_live"))).isEqualTo(500);
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
