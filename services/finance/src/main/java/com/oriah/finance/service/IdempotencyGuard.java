package com.oriah.finance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.events.EventJson;
import com.oriah.finance.exception.FinanceProblem;

/**
 * Every write is safe to send twice: the caller's {@code Idempotency-Key} (1 to 128 characters, theirs alone) names the write, and what was asked is fingerprinted with the
 * path. The same key with the same request is the same write: the original answer is returned, nothing is done again. The same key with ANOTHER request is a mistake and is
 * refused. Two requests with one key at the same moment are taken one at a time.
 */
@Component
public class IdempotencyGuard {

    /** What a guarded write answered, and whether this is the answer to an earlier sending of it. */
    public record Guarded<T>(T body, boolean replay) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Guarded<T> run(String actor, String key, String methodPath, Object request, Class<T> type, Supplier<T> write) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "idempotency-key-required", "Send an Idempotency-Key header of 1 to 128 characters: it makes a double click the same write");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> { }, "idem:" + actor + ":" + key);
        String fingerprint = fingerprint(methodPath, request);
        List<String[]> held = jdbc.query("SELECT fingerprint, result_ref::text FROM idempotency WHERE created_by = ? AND key = ?", (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, actor, key);
        if (!held.isEmpty()) {
            if (!held.get(0)[0].equals(fingerprint)) {
                throw new FinanceProblem(HttpStatus.CONFLICT, "idempotency-key-reused", "That Idempotency-Key was already used for a different request: use a new one for a new write");
            }
            return new Guarded<>(EventJson.mapper().readValue(held.get(0)[1], type), true);
        }
        T answer = write.get();
        jdbc.update("INSERT INTO idempotency (created_by, key, method_path, fingerprint, result_ref) VALUES (?, ?, ?, ?, CAST(? AS jsonb))", actor, key, methodPath, fingerprint,
                EventJson.write(answer));
        return new Guarded<>(answer, false);
    }

    private static String fingerprint(String methodPath, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((methodPath + "\n" + EventJson.write(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
