package com.oriah.notification.controller;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.text.MessageTexts;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The two reads for staff (Phase 11). Neither changes anything, and neither answers an address.
 *
 * <p>They are in a controller of their own, with NO class-level "shopper" rule: the owner in production holds every capability and is not a
 * shopper ({@code USER}), and must still be able to read both.
 *
 * <p><b>Alerts</b> are shown by the capability that guards the list each kind points at: money alerts (a customer says they have paid, money
 * recorded but a sale not finished ...) to {@code SALES_AGENT}; cost alerts (a piece below cost, the check not running) to a caller holding
 * {@code CATALOG_ADMIN} and {@code COST_VIEWER} both. A kind the caller may not see is absent from the answer, not an error.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "A parameter that is not one of the values allowed",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the required capability",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class StaffReadsController {

    private static final int MOST = 100;
    private static final Duration WORK_LIST_GOES_BACK = Duration.ofDays(14);
    /** The alerts whose words say the system needs attention: they end with who to turn to, as configured now. */
    private static final Set<String> SAY_WHO_TO_TURN_TO = Set.of("SETTLEMENT_STUCK", "BELOW_COST_CHECK_DOWN");

    private final JdbcTemplate jdbc;
    private final NotificationSettings settings;
    private final EmailSetup setup;
    private final Clock clock;

    public StaffReadsController(JdbcTemplate jdbc, NotificationSettings settings, EmailSetup setup, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.setup = setup;
        this.clock = clock;
    }

    /** One alert, as a screen lists it. Never a mailbox. */
    public record StaffAlertResponse(
            UUID id,
            @Schema(description = "PAYMENT_TO_CONFIRM, SETTLEMENT_STUCK, VOID_AFTER_SALE, CLAIM_EMAIL_LIMIT, EMAIL_DOWN, BELOW_COST or BELOW_COST_CHECK_DOWN")
            String kind,
            @Schema(description = "MONEY (sales staff) or COST (who may see costs and set prices)", allowableValues = {"MONEY", "COST"})
            String audience,
            @Schema(allowableValues = {"OPEN", "RESOLVED"})
            String state,
            @Schema(description = "The order it is about, when it is about one", nullable = true)
            UUID orderId,
            @Schema(description = "What it is about: an order id, a payment confirmation id, a variant id, a day, or a fixed word")
            String subjectId,
            @Schema(description = "One line in plain words, for a list")
            String summary,
            @Schema(description = "The full words. For an alert that says the system needs attention it ends with who to turn to, as configured now.")
            String detail,
            Instant raisedAt,
            @Schema(description = "When it is next told again if still open", nullable = true)
            Instant remindAt,
            @Schema(nullable = true)
            Instant resolvedAt,
            @Schema(description = "How it ended: by a fact (PAID, EXPIRED, CANCELLED, MONEY_RECORDED, UNSETTLED, VOIDED, NO_LONGER_BELOW, RECOVERED) or "
                    + "BY_CLOCK (nothing ended it before its time ran out)", nullable = true)
            String resolvedHow,
            @Schema(description = "When a mail server first took an email about it; null if nobody has been told by email", nullable = true)
            Instant emailedAt) {
    }

    /** One message about an order, as sales staff read it. Never its owner, never an address, never an alert. */
    public record StaffMessageResponse(
            UUID id,
            UUID orderId,
            String eventType,
            @Schema(description = "SHOPPER (a customer with an account) or CALLER (a customer who ordered by phone)", allowableValues = {"SHOPPER", "CALLER"})
            String recipientKind,
            @Schema(description = "email, or by-hand: words for staff to send themselves", allowableValues = {"email", "by-hand"})
            String channel,
            String subject,
            String body,
            @Schema(allowableValues = {"RECORDED", "SENT", "FAILED", "NOT_SENT", "BY_HAND"})
            String status,
            @Schema(nullable = true)
            String statusReason,
            Instant recordedAt,
            @Schema(nullable = true)
            Instant sentAt,
            @Schema(description = "True when what was emailed was the short 'please sign in' note (an address nobody confirmed), not these words")
            boolean shortForm) {
    }

    @Operation(operationId = "listStaffAlerts", summary = "What needs somebody: the alerts the caller may see, newest first",
            description = "At most 100 per audience. OPEN (the default) also lists, for 30 days, money recorded whose sale was never heard to "
                    + "complete (closed BY_CLOCK). Money alerts need SALES_AGENT; cost alerts need CATALOG_ADMIN and COST_VIEWER both.")
    @Tag(name = "Staff alerts", description = "Things staff must look at")
    @ApiResponse(responseCode = "200", description = "The alerts the caller may see, possibly none")
    @GetMapping("/alerts")
    @PreAuthorize("hasRole('SALES_AGENT') or (hasRole('CATALOG_ADMIN') and hasRole('COST_VIEWER'))")
    public List<StaffAlertResponse> alerts(
            @Parameter(description = "OPEN (default) or ALL") @RequestParam(name = "state", defaultValue = "OPEN") String state,
            @Parameter(description = "MONEY or COST, to narrow the list") @RequestParam(name = "audience", required = false) String audience,
            Authentication caller) {
        boolean all = switch (state) {
            case "OPEN" -> false;
            case "ALL" -> true;
            default -> throw new IllegalArgumentException("state must be OPEN or ALL");
        };
        if (audience != null && !audience.equals("MONEY") && !audience.equals("COST")) {
            throw new IllegalArgumentException("audience must be MONEY or COST");
        }
        List<String> mayRead = new ArrayList<>();
        if (holds(caller, "SALES_AGENT") && !"COST".equals(audience)) {
            mayRead.add("MONEY");
        }
        if (holds(caller, "CATALOG_ADMIN") && holds(caller, "COST_VIEWER") && !"MONEY".equals(audience)) {
            mayRead.add("COST");
        }
        if (mayRead.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<Object> arguments = new ArrayList<>(mayRead);
        arguments.add(all);
        arguments.add(Timestamp.from(now.minus(settings.alerts().listUnansweredFor())));
        arguments.add(MOST);
        // The audience is in the WHERE, before the limit: a hundred of one group can never hide an alert of the other.
        return jdbc.query("""
                SELECT * FROM (
                    SELECT a.*, row_number() OVER (PARTITION BY a.audience ORDER BY a.raised_at DESC, a.id) AS place
                      FROM staff_alert a
                     WHERE a.audience IN (%s)
                       AND (? OR a.state = 'OPEN'
                              OR (a.kind = 'SETTLEMENT_STUCK' AND a.resolved_how = 'BY_CLOCK' AND a.resolved_at > ?))) listed
                 WHERE place <= ? ORDER BY raised_at DESC, id""".formatted(String.join(", ", Collections.nCopies(mayRead.size(), "?"))),
                (row, n) -> new StaffAlertResponse(row.getObject("id", UUID.class), row.getString("kind"), row.getString("audience"),
                        row.getString("state"), row.getObject("order_id", UUID.class), row.getString("subject_id"), row.getString("summary"),
                        detail(row.getString("kind"), row.getString("detail")), instant(row.getTimestamp("raised_at")),
                        instant(row.getTimestamp("remind_at")), instant(row.getTimestamp("resolved_at")), row.getString("resolved_how"),
                        instant(row.getTimestamp("emailed_at"))),
                arguments.toArray());
    }

    @Operation(operationId = "listStaffMessages", summary = "What a customer was told about an order, or the words waiting to be sent by hand",
            description = "Give orderId for one order's messages, oldest first; or status=BY_HAND for the work list of words to send to customers "
                    + "who ordered by phone, newest first, at most 100, from 'since' (default: the last 14 days). Exactly one of the two.")
    @Tag(name = "Staff messages", description = "What customers were told")
    @ApiResponse(responseCode = "200", description = "The messages, possibly none (an order nobody was told anything about is an empty list)")
    @GetMapping("/messages")
    @PreAuthorize("hasRole('SALES_AGENT')")
    public List<StaffMessageResponse> messages(
            @Parameter(description = "The order whose messages to list") @RequestParam(name = "orderId", required = false) String orderId,
            @Parameter(description = "BY_HAND, for the work list") @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "With status=BY_HAND: only words written since this instant") @RequestParam(name = "since", required = false) String since) {
        if ((orderId == null) == (status == null)) {
            throw new IllegalArgumentException("give either orderId or status=BY_HAND, not both and not neither");
        }
        if (orderId != null) {
            UUID order;
            try {
                order = UUID.fromString(orderId);
            } catch (IllegalArgumentException notAnId) {
                throw new IllegalArgumentException("orderId must be an order's id");
            }
            return jdbc.query("""
                    SELECT * FROM notification_log WHERE order_id = ? AND recipient_kind IN ('SHOPPER', 'CALLER')
                     ORDER BY recorded_at, id""", StaffReadsController::message, order);
        }
        if (!"BY_HAND".equals(status)) {
            throw new IllegalArgumentException("status must be BY_HAND");
        }
        Instant from;
        try {
            from = since == null ? clock.instant().minus(WORK_LIST_GOES_BACK) : Instant.parse(since);
        } catch (DateTimeParseException notAnInstant) {
            throw new IllegalArgumentException("since must be an instant, for example 2026-11-09T07:30:00Z");
        }
        return jdbc.query("""
                SELECT * FROM notification_log WHERE status = 'BY_HAND' AND recorded_at >= ?
                 ORDER BY recorded_at DESC, id LIMIT ?""", StaffReadsController::message, Timestamp.from(from), MOST);
    }

    private static StaffMessageResponse message(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new StaffMessageResponse(row.getObject("id", UUID.class), row.getObject("order_id", UUID.class), row.getString("event_type"),
                row.getString("recipient_kind"), row.getString("channel"), row.getString("subject"), row.getString("body"), row.getString("status"),
                row.getString("status_reason"), instant(row.getTimestamp("recorded_at")), instant(row.getTimestamp("sent_at")),
                row.getBoolean("short_form"));
    }

    /** The stored words, with who to turn to added for the kinds that say the system needs attention. That line is never stored. */
    private String detail(String kind, String stored) {
        if (!SAY_WHO_TO_TURN_TO.contains(kind)) {
            return stored;
        }
        String support = setup.supportLine() == null || setup.supportLine().isBlank() ? MessageTexts.NOBODY_NAMED_FOR_SUPPORT : setup.supportLine();
        return stored + " " + support;
    }

    private static boolean holds(Authentication caller, String capability) {
        return caller.getAuthorities().stream().anyMatch(authority -> ("ROLE_" + capability).equals(authority.getAuthority()));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
