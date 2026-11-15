package com.oriah.order.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.oriah.order.dto.RecordOrderRequest;

/**
 * The fingerprint of a recording request (Phase 9): the same Idempotency-Key with the same request is the same order, with another request it is refused
 * ({@code idempotency-key-reused}). It covers everything that MAKES the order and nothing that only CONFIRMS it (the total the person read out, the reason for
 * a late date), so a repeat after a lost reply, sent by whatever the screen sends, is still the same order. Each part is length-prefixed, so no field can pass
 * for two. Kept on the order in its own column; it holds no readable personal data (a hash).
 */
final class RequestHash {

    private RequestHash() {
    }

    /**
     * @param callerName     the caller's name as it will be kept (tidied)
     * @param callerPhone    the caller's phone as it will be kept ({@code +260...}), or null
     * @param recipientName  who the parcel is for as it will be kept, or null
     * @param recipientPhone their phone as it will be kept, or null
     */
    static String of(RecordOrderRequest r, String callerName, String callerPhone, String recipientName, String recipientPhone) {
        StringBuilder canonical = new StringBuilder();
        part(canonical, r.kind());
        part(canonical, callerName);
        part(canonical, callerPhone);
        part(canonical, r.lines().size());
        r.lines().forEach(l -> {
            part(canonical, l.variantId());
            part(canonical, l.quantity());
            part(canonical, l.boxVariantId());
        });
        var d = r.delivery();
        part(canonical, d.mode());
        part(canonical, d.optionCode());
        part(canonical, d.townId());
        part(canonical, d.payer());
        part(canonical, d.agreedAmount() == null ? null : d.agreedAmount().toPlainString());
        part(canonical, d.agreedReason() == null ? null : d.agreedReason().strip());
        part(canonical, recipientName);
        part(canonical, recipientPhone);
        var a = r.address();
        part(canonical, a == null ? null : a.line1());
        part(canonical, a == null ? null : a.line2());
        part(canonical, a == null ? null : a.city());
        part(canonical, a == null ? null : a.state());
        part(canonical, a == null ? null : a.postalCode());
        part(canonical, a == null ? null : a.country());
        part(canonical, r.occurredAt());
        part(canonical, r.slipReference() == null ? null : r.slipReference().strip());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static void part(StringBuilder out, Object value) {
        String text = value == null ? "\u0000" : String.valueOf(value);
        out.append(text.length()).append(':').append(text).append(';');
    }
}
