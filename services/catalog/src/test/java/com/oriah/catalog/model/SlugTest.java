package com.oriah.catalog.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slug generation, which has to agree with SQL.
 *
 * <p>V3 backfilled {@code products.slug} for every pre-existing product with
 * {@code trim(both '-' from regexp_replace(lower(title), '[^a-z0-9]+', '-',
 * 'g'))}. This class produces slugs for products created from now on. If the
 * two drift, the same title yields a different URL depending on whether the
 * product predates the migration — and because the column is UNIQUE, a drift
 * that happens to collide surfaces as a constraint violation on save rather
 * than as anything legible.
 *
 * <p>So the cases below are written as the migration's regex behaviour, not
 * as whatever this implementation happens to do.
 */
class SlugTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "Aurelia Solitaire Ring,          aurelia-solitaire-ring",
            "Untagged Cuff,                   untagged-cuff",
            // Runs of non-alphanumerics collapse to a single hyphen, matching
            // the regex's '+' quantifier.
            "Oriah  --  Signature,            oriah-signature",
            "18k  Gold,                       18k-gold",
            // Leading and trailing separators are trimmed, not left dangling.
            "'  Leading and trailing  ',      leading-and-trailing",
            "...Ellipsis...,                  ellipsis",
            // Punctuation inside a word is a separator like any other.
            "Women's Rings,                   women-s-rings",
            "Rose-Gold,                       rose-gold",
            "A/B Test,                        a-b-test",
            // Non-ASCII is not in [a-z0-9], so it separates. Deliberate: it is
            // what the SQL does, and a URL of percent-escapes is worse than a
            // readable approximation.
            "Rosé Gold,                       ros-gold",
            "Café Noir Watch,                 caf-noir-watch",
    })
    @DisplayName("titles become slugs the same way the V3 migration's regex does")
    void matchesTheMigrationsRegex(String title, String expected) {
        assertThat(Slug.from(title)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a title that strips away entirely falls back rather than producing an empty slug")
    void emptyResultFallsBack() {
        // slug is NOT NULL UNIQUE, so an empty string would be storable once
        // and then collide forever. The migration guards this with
        // nullif(..., '') and a coalesce; this side must guard it too.
        assertThat(Slug.from("!!!")).isNotEmpty().isEqualTo("product");
        assertThat(Slug.from("---")).isEqualTo("product");
        assertThat(Slug.from("。。。")).isEqualTo("product");
    }

    @Test
    @DisplayName("a blank title is rejected, because a product cannot have one anyway")
    void blankTitleRejected() {
        assertThat(Slug.from("x")).isEqualTo("x");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Slug.from(null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Slug.from("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
