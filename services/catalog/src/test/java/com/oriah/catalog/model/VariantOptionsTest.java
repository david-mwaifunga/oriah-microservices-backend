package com.oriah.catalog.model;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The option digest is what makes "the same variant twice" a DATABASE error
 * rather than a race between two admins, via
 * {@code UNIQUE (product_id, option_digest)}. That only holds if the digest is
 * canonical: two spellings of the same combination must produce one digest,
 * and two different combinations must never produce the same one.
 *
 * <p>An earlier design joined sorted {@code key=value} pairs with {@code '|'}.
 * The adversarial review killed it, correctly: that scheme collides as soon as
 * a key or value contains {@code '='} or {@code '|'}, and treats two Unicode
 * normalisations of the same string as different options. Both cases are
 * pinned below, because both are reachable from an admin form.
 */
class VariantOptionsTest {

    private static Map<String, String> map(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("canonical form")
    class Canonical {

        @Test
        @DisplayName("an empty option map canonicalises to {} — the default-variant case")
        void emptyMap() {
            // A one-off piece varies on no axis at all, so this is the shape
            // the V3 backfill produces for every pre-existing product. The
            // migration computes sha256('{}') in SQL, so this exact string is
            // the contract between the two.
            assertThat(VariantOptions.canonicalJson(Map.of())).isEqualTo("{}");
        }

        @Test
        @DisplayName("keys are sorted, so insertion order cannot change the digest")
        void keyOrderIsIrrelevant() {
            var a = VariantOptions.canonicalJson(map("Metal", "18k", "Ring Size", "M"));
            var b = VariantOptions.canonicalJson(map("Ring Size", "M", "Metal", "18k"));

            assertThat(a).isEqualTo(b);
            assertThat(a).isEqualTo("{\"Metal\":\"18k\",\"Ring Size\":\"M\"}");
        }

        @Test
        @DisplayName("values are trimmed, so a stray space is not a new variant")
        void valuesAreTrimmed() {
            assertThat(VariantOptions.canonicalJson(map("Ring Size", "  M  ")))
                    .isEqualTo(VariantOptions.canonicalJson(map("Ring Size", "M")));
        }

        @Test
        @DisplayName("quotes and backslashes are JSON-escaped rather than concatenated raw")
        void escapesJsonSyntax() {
            // The whole point of moving to JSON: the separator is structural,
            // not a character that can appear in the data.
            assertThat(VariantOptions.canonicalJson(map("Engraving", "say \"hi\"")))
                    .isEqualTo("{\"Engraving\":\"say \\\"hi\\\"\"}");
            assertThat(VariantOptions.canonicalJson(map("Path", "a\\b")))
                    .isEqualTo("{\"Path\":\"a\\\\b\"}");
        }
    }

    @Nested
    @DisplayName("digest")
    class Digest {

        @Test
        @DisplayName("is 64 lowercase hex characters, matching the CHAR(64) column")
        void shapeMatchesTheColumn() {
            assertThat(VariantOptions.digest(map("Ring Size", "M")))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("the empty map's digest is sha256 of \"{}\" — the value V3 wrote for every backfilled variant")
        void emptyMapDigestMatchesTheMigration() {
            // V3 inserts encode(sha256('{}'::bytea), 'hex'). If this ever
            // drifts, every legacy variant's digest stops matching what the
            // application would compute, and the uniqueness constraint starts
            // allowing a duplicate default variant.
            assertThat(VariantOptions.digest(Map.of()))
                    .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        }

        @Test
        @DisplayName("the '=' and '|' collision the old k=v|k=v scheme allowed is gone")
        void noSeparatorCollision() {
            // Under "sorted k=v joined by |", both of these flatten to the
            // same string: a=b|c=d. They are different variants and must
            // never share a digest.
            var first = VariantOptions.digest(map("a", "b", "c", "d"));
            var second = VariantOptions.digest(map("a", "b|c=d"));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("two Unicode normalisations of the same option are ONE variant")
        void normalisationFormsCollapse() {
            // "Rosé" composed (U+00E9) vs decomposed (e + U+0301). These look
            // identical in an admin form and in the storefront, so treating
            // them as two variants would let a product hold a duplicate the
            // operator cannot tell apart.
            String composed = Normalizer.normalize("Rosé Gold", Normalizer.Form.NFC);
            String decomposed = Normalizer.normalize("Rosé Gold", Normalizer.Form.NFD);
            assertThat(composed).isNotEqualTo(decomposed); // genuinely different strings

            assertThat(VariantOptions.digest(map("Metal", composed)))
                    .isEqualTo(VariantOptions.digest(map("Metal", decomposed)));
            assertThat(VariantOptions.digest(map(composed, "x")))
                    .isEqualTo(VariantOptions.digest(map(decomposed, "x")));
        }

        @Test
        @DisplayName("different combinations produce different digests")
        void differentCombinationsDiffer() {
            assertThat(VariantOptions.digest(map("Ring Size", "M")))
                    .isNotEqualTo(VariantOptions.digest(map("Ring Size", "L")));
            assertThat(VariantOptions.digest(map("Ring Size", "M")))
                    .isNotEqualTo(VariantOptions.digest(map("Ring Size", "M", "Metal", "18k")));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a blank key or value is rejected")
        void blanksRejected() {
            assertThatThrownBy(() -> VariantOptions.validated(map("", "M")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VariantOptions.validated(map("Ring Size", "  ")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("two keys that normalise to the same name are refused, not merged with one value silently winning")
        void collidingKeysAreRefused() {
            // "Size" and " Size " are different raw keys in a JSON object but the
            // same axis once trimmed. Merged into one map entry, the later value
            // overwrote the earlier and the variant was stored with an option
            // combination nobody submitted.
            var trimmed = new LinkedHashMap<String, String>();
            trimmed.put("Size", "M");
            trimmed.put(" Size ", "L");
            assertThatThrownBy(() -> VariantOptions.validated(trimmed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Size");

            // The same through Unicode: precomposed e-acute and e + combining acute.
            var composed = new LinkedHashMap<String, String>();
            composed.put("Café", "A");
            composed.put("Café", "B");
            assertThatThrownBy(() -> VariantOptions.validated(composed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more than once");
        }

        @Test
        @DisplayName("keys and values longer than 64 characters are rejected")
        void lengthCapped() {
            assertThatThrownBy(() -> VariantOptions.validated(map("k".repeat(65), "v")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VariantOptions.validated(map("k", "v".repeat(65))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("control characters are rejected rather than escaped into the digest")
        void controlCharactersRejected() {
            // Permitting these would mean a newline inside an option name,
            // which no admin form can round-trip and which would render as a
            // broken selector label.
            assertThatThrownBy(() -> VariantOptions.validated(map("Ring\nSize", "M")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> VariantOptions.validated(map("Ring Size", "M\u0000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("validation returns the NFC-normalised, trimmed map that gets stored")
        void returnsNormalisedMap() {
            // The stored JSONB must match what was digested, or a round trip
            // would recompute a different digest and break the constraint.
            var out = VariantOptions.validated(
                    map("  Metal  ", "  " + Normalizer.normalize("Rosé", Normalizer.Form.NFD) + "  "));

            assertThat(out).containsExactly(
                    Map.entry("Metal", Normalizer.normalize("Rosé", Normalizer.Form.NFC)));
        }

        @Test
        @DisplayName("an empty map is valid — that is the one-off piece")
        void emptyIsValid() {
            assertThat(VariantOptions.validated(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("a null map is treated as empty rather than exploding")
        void nullIsEmpty() {
            assertThat(VariantOptions.validated(null)).isEmpty();
        }
    }
}
