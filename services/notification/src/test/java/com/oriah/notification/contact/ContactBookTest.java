package com.oriah.notification.contact;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where to email a customer. Notification is never told an address by another service: it learns it from the customer's own sign-in token,
 * each time the website's bell asks for their messages, together with whether the address was ever confirmed.
 *
 * <p>One row per person, in one place ({@code recipient_contact}). Never on a message row, in an event or in a log line. A token issued
 * earlier never replaces what a later one taught (a browser tab can hold an old token for half an hour after the customer changed their address).
 */
@ServiceTest
@ExtendWith(OutputCaptureExtension.class)
class ContactBookTest {

    private static final Instant ISSUED = Instant.parse("2026-11-09T07:00:00Z");

    @Autowired
    private ContactBook contacts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    private static Jwt token(String subject, String email, Object verified, Instant issuedAt) {
        Jwt.Builder token = Jwt.withTokenValue("a-token").header("alg", "none").subject(subject);
        if (email != null) {
            token.claim("email", email);
        }
        if (verified != null) {
            token.claim("email_verified", verified);
        }
        if (issuedAt != null) {
            token.issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(1800));
        }
        return token.build();
    }

    @Test
    @DisplayName("a customer's own token teaches their address and that it is confirmed")
    void aShoppersTokenTeachesTheirAddress() {
        contacts.seen(token("kc-alice", " alice@oriah.local ", true, ISSUED));

        assertThat(contacts.find("kc-alice")).hasValueSatisfying(contact -> {
            assertThat(contact.email()).isEqualTo("alice@oriah.local");
            assertThat(contact.verified()).isTrue();
        });
        assertThat(contacts.find("kc-nobody")).isEmpty();
    }

    @Test
    @DisplayName("an address never confirmed is remembered as not confirmed; so is one whose token does not say")
    void unverifiedIsRememberedAsUnverified() {
        contacts.seen(token("kc-bob", "bob@oriah.local", false, ISSUED));
        contacts.seen(token("kc-carol", "carol@oriah.local", null, ISSUED));
        contacts.seen(token("kc-dan", "dan@oriah.local", "true", ISSUED));

        assertThat(contacts.find("kc-bob").orElseThrow().verified()).isFalse();
        assertThat(contacts.find("kc-carol").orElseThrow().verified()).isFalse();
        assertThat(contacts.find("kc-dan").orElseThrow().verified()).as("only the real value true counts, not the text").isFalse();
    }

    @Test
    @DisplayName("a later token with another address replaces the old one")
    void aChangedAddressReplacesTheOldOne() {
        contacts.seen(token("kc-alice", "alice@oriah.local", true, ISSUED));
        contacts.seen(token("kc-alice", "alice.new@oriah.local", false, ISSUED.plusSeconds(600)));

        assertThat(contacts.find("kc-alice")).hasValueSatisfying(contact -> {
            assertThat(contact.email()).isEqualTo("alice.new@oriah.local");
            assertThat(contact.verified()).isFalse();
        });
    }

    @Test
    @DisplayName("an older token never replaces what a newer one taught")
    void anOlderTokenNeverReplacesANewerAddress() {
        contacts.seen(token("kc-alice", "alice.new@oriah.local", true, ISSUED.plusSeconds(600)));
        contacts.seen(token("kc-alice", "alice.old@oriah.local", true, ISSUED));

        assertThat(contacts.find("kc-alice").orElseThrow().email()).isEqualTo("alice.new@oriah.local");
    }

    @Test
    @DisplayName("a token with no email, no issue time or no subject teaches nothing")
    void aTokenWithNoEmailTeachesNothing() {
        contacts.seen(token("kc-alice", null, true, ISSUED));
        contacts.seen(token("kc-bob", "bob@oriah.local", true, null));
        contacts.seen(null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipient_contact", Long.class)).isZero();
    }

    @Test
    @DisplayName("a text that is not exactly one address is not kept, and what was known stays")
    void anUnreadableAddressIsNotKept() {
        contacts.seen(token("kc-alice", "alice@oriah.local", true, ISSUED));
        contacts.seen(token("kc-alice", "Alice <alice@oriah.local>, other@oriah.local", true, ISSUED.plusSeconds(60)));
        contacts.seen(token("kc-bob", "not-an-address", true, ISSUED));

        assertThat(contacts.find("kc-alice").orElseThrow().email()).isEqualTo("alice@oriah.local");
        assertThat(contacts.find("kc-bob")).isEmpty();
    }

    @Test
    @DisplayName("the same token again writes nothing; 'last seen' moves at most once a day")
    void seenAtMovesAtMostOnceADay() {
        contacts.seen(token("kc-alice", "alice@oriah.local", true, ISSUED));
        Instant first = seenAt("kc-alice");
        assertThat(first).isEqualTo(MutableClock.START);

        clock.advance(Duration.ofHours(2));
        contacts.seen(token("kc-alice", "alice@oriah.local", true, ISSUED));
        assertThat(seenAt("kc-alice")).as("the bell asks on every page: that is not a write each time").isEqualTo(first);

        clock.advance(Duration.ofHours(23));
        contacts.seen(token("kc-alice", "alice@oriah.local", true, ISSUED));
        assertThat(seenAt("kc-alice")).isEqualTo(MutableClock.START.plus(Duration.ofHours(25)));
        assertThat(jdbc.queryForObject("SELECT first_seen_at FROM recipient_contact WHERE keycloak_id = 'kc-alice'", java.sql.Timestamp.class).toInstant())
                .isEqualTo(MutableClock.START);
    }

    @Test
    @DisplayName("learning an address never throws, whatever goes wrong, and the warning names no address")
    void learningNeverThrows(CapturedOutput output) {
        jdbc.execute("ALTER TABLE recipient_contact RENAME TO recipient_contact_away");
        try {
            contacts.seen(token("kc-alice", "alice.secret@oriah.local", true, ISSUED));
        } finally {
            jdbc.execute("ALTER TABLE recipient_contact_away RENAME TO recipient_contact");
        }

        assertThat(output.getAll()).contains("could not be remembered").doesNotContain("alice.secret");
    }

    @Test
    @DisplayName("a refused row is never quoted by the database's error: no address can reach a log through one")
    void aConstraintFailureNamesNoAddress() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO recipient_contact (keycloak_id, email, email_verified, token_issued_at, first_seen_at, seen_at)
                VALUES (?, ?, TRUE, now(), now(), now())""", UUID.randomUUID().toString(), " someone.private@oriah.local"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("recipient_contact_email_shape")
                .hasMessageNotContaining("someone.private");
    }

    private Instant seenAt(String keycloakId) {
        return jdbc.queryForObject("SELECT seen_at FROM recipient_contact WHERE keycloak_id = ?", java.sql.Timestamp.class, keycloakId).toInstant();
    }
}
