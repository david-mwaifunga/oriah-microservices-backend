package com.oriah.payment.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.confirmation.HeldMoneyView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Money held for customers (Phase 7): it came in and could not pay a sale, so it is OWED back, never revenue. PAYMENT_ADMIN (the owner), here and
 * at the gateway: the staff who confirm money see their own confirmation's outcome, not this list. Repaying it is a later phase's.
 */
@RestController
@RequestMapping("/api/v1/admin/payments/held-money")
@Tag(name = "Held money (admin)", description = "Money owed back to customers; PAYMENT_ADMIN")
@PreAuthorize("hasRole('PAYMENT_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking PAYMENT_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class HeldMoneyController {

    private final JdbcTemplate jdbc;

    public HeldMoneyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(operationId = "listHeldMoney", summary = "Money owed back to customers, newest first",
            description = "`status` is OWED (the default), VOIDED (its confirmation was voided as keyed wrongly) or ALL.")
    @ApiResponse(responseCode = "400", description = "A status that does not exist",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The held money, newest first")
    @GetMapping
    @Transactional(readOnly = true)
    public List<HeldMoneyView> list(@RequestParam(name = "status", defaultValue = "OWED") String status) {
        String wanted = status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("OWED", "VOIDED", "ALL").contains(wanted)) {
            throw new IllegalArgumentException("status must be OWED, VOIDED or ALL, was '" + status + "'");
        }
        String where = "ALL".equals(wanted) ? "" : " WHERE h.status = ?";
        String sql = """
                SELECT h.id, h.confirmation_id, h.order_id, h.reason, h.owed_gross, h.currency, h.status, h.created_at, h.voided_at,
                       c.method, c.reference, c.arrived_at, c.confirmed_by
                  FROM held_money h JOIN payment_confirmation c ON c.id = h.confirmation_id""" + where + " ORDER BY h.created_at DESC, h.id";
        org.springframework.jdbc.core.RowMapper<HeldMoneyView> mapper = (rs, n) -> {
            java.sql.Timestamp voidedAt = rs.getTimestamp("voided_at");
            return new HeldMoneyView(rs.getObject("id", java.util.UUID.class), rs.getObject("confirmation_id", java.util.UUID.class),
                    rs.getObject("order_id", java.util.UUID.class), rs.getString("reason"), rs.getBigDecimal("owed_gross"), rs.getString("currency"),
                    rs.getString("status"), rs.getTimestamp("created_at").toInstant(), voidedAt == null ? null : voidedAt.toInstant(), rs.getString("method"),
                    rs.getString("reference"), rs.getTimestamp("arrived_at").toInstant(), rs.getString("confirmed_by"));
        };
        return "ALL".equals(wanted) ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, wanted);
    }
}
