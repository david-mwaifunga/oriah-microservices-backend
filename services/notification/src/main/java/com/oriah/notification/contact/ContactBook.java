package com.oriah.notification.contact;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where to email a customer ({@code recipient_contact}).
 *
 * <p><b>Where the address comes from.</b> No other service is asked. Each time the website's bell asks for a customer's messages
 * ({@code GET /api/v1/notifications/me}), their own sign-in token says their address ({@code email}), whether it was ever confirmed
 * ({@code email_verified}) and when the token was issued ({@code iat}). That is noted here: one row per person, changed in place.
 * A customer who never opens the shop after ordering is therefore never emailed (their message says "no address" in the end), and the
 * storefront must keep asking for the bell once per signed-in session.
 *
 * <p><b>An older token never wins.</b> A browser tab can hold a token for half an hour after the customer changed their address elsewhere, so
 * a row is replaced only by a token issued later. The same token again changes nothing, except that "last seen" moves once a day.
 *
 * <p><b>It never fails its caller.</b> Noting an address is a small favour done while answering a read. It runs in a transaction of its own
 * and swallows every failure with one warning that names no address, so the bell is answered whatever happens here.
 *
 * <p>The address is read again each time a message is sent. It is never copied onto a message row, into an event or into a log line.
 */
@Component
public class ContactBook {

    private static final Logger log = LoggerFactory.getLogger(ContactBook.class);

    /**
     * @param verified true only when the customer's sign-in provider says the address was confirmed; an address nobody confirmed may be a
     *                 stranger's
     */
    public record Contact(String email, boolean verified) {

        /** Never the address: this is what a log line would print. */
        @Override
        public String toString() {
            return "Contact[" + (verified ? "confirmed" : "not confirmed") + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public ContactBook(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Notes what a customer's own token says about their address. Never throws. */
    public void seen(Jwt jwt) {
        try {
            if (jwt == null || jwt.getSubject() == null || jwt.getIssuedAt() == null) {
                return;
            }
            Optional<String> email = EmailAddressRule.oneAddress(jwt.getClaimAsString("email"));
            if (email.isEmpty()) {
                // No address in the token, or a text that is not exactly one address: nothing is learnt, and what was known stays.
                return;
            }
            // Only the real value true. A token that does not say is "not confirmed".
            boolean verified = Boolean.TRUE.equals(jwt.getClaim("email_verified"));
            Timestamp issuedAt = Timestamp.from(jwt.getIssuedAt());
            Timestamp now = Timestamp.from(clock.instant());
            ownTransaction.executeWithoutResult(transaction -> jdbc.update("""
                    INSERT INTO recipient_contact (keycloak_id, email, email_verified, token_issued_at, first_seen_at, seen_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (keycloak_id) DO UPDATE
                       SET email = EXCLUDED.email, email_verified = EXCLUDED.email_verified, token_issued_at = EXCLUDED.token_issued_at,
                           seen_at = EXCLUDED.seen_at
                     WHERE recipient_contact.token_issued_at < EXCLUDED.token_issued_at
                        OR (recipient_contact.token_issued_at = EXCLUDED.token_issued_at
                            AND recipient_contact.seen_at <= EXCLUDED.seen_at - interval '1 day')""",
                    jwt.getSubject(), email.get(), verified, issuedAt, now, now));
        } catch (RuntimeException failure) {
            // The kind of failure only: its message could quote the row.
            log.warn("A customer's email address could not be remembered ({}); their messages are still shown on the website",
                    failure.getClass().getSimpleName());
        }
    }

    public Optional<Contact> find(String keycloakId) {
        List<Contact> found = jdbc.query("SELECT email, email_verified FROM recipient_contact WHERE keycloak_id = ?",
                (row, n) -> new Contact(row.getString("email"), row.getBoolean("email_verified")), keycloakId);
        return found.stream().findFirst();
    }

    /** When the address was last seen in a token; for tests and for a future clean-up of addresses nobody uses. */
    Optional<Instant> lastSeen(String keycloakId) {
        return jdbc.query("SELECT seen_at FROM recipient_contact WHERE keycloak_id = ?", (row, n) -> row.getTimestamp(1).toInstant(), keycloakId)
                .stream().findFirst();
    }
}
