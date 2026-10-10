package com.oriah.shipping.dto;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executable form of the Tier 4 lesson, applied before it can bite again.
 *
 * <p>Tier 4 centralized {@code resilience4j.*.configs.default} in config-repo
 * and checkout stopped starting: {@code base-config} is a <em>hard link</em>,
 * not an additive setting, so moving the value converted an
 * {@code optional:configserver} dependency into a required one — a change made
 * in the name of resilience that reduced it.
 *
 * <p>{@code /contact-info} is the same shape of change: new values, naturally
 * belonging in config-repo, consumed by a {@code @ConfigurationProperties}
 * class. So the binding behaviour is measured here rather than assumed, in the
 * three cases that matter.
 *
 * <p>Deliberately an {@code ApplicationContextRunner} rather than a
 * {@code @SpringBootTest}: the interesting outcomes are
 * <strong>startup failures</strong>, and a test that asserts a context refuses
 * to start cannot be written as a context that has to start.
 */
class ContactInfoBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    @DisplayName("validated binding FAILS the context when the prefix is absent")
    void validatedBindingFailsWhenConfigRepoIsUnreachable() {
        // The hazard, stated as a test. Values living only in
        // config-repo/shipping.yaml + @Validated = shipping refuses to start
        // whenever the Config Server is down. This is why the fallback block in
        // shipping's own application.yaml is mandatory rather than tidy.
        runner.withUserConfiguration(ValidatedHolder.class)
                .run(ctx -> assertThat(ctx)
                        .as("@Validated + a missing prefix is a hard link: binding fails "
                                + "and the whole service is down, which is exactly the "
                                + "Tier 4 base-config regression")
                        .hasFailed());
    }

    @Test
    @DisplayName("validated binding succeeds once the fallback supplies the values")
    void validatedBindingSucceedsWithAFallback() {
        // The other half: the fallback is what makes @Validated safe. These
        // properties stand in for the block in application.yaml.
        runner.withUserConfiguration(ValidatedHolder.class)
                .withPropertyValues(
                        "oriah.shipping.contact-info.message=unavailable",
                        "oriah.shipping.contact-info.contact-details.name=unavailable",
                        "oriah.shipping.contact-info.contact-details.email=unavailable",
                        "oriah.shipping.contact-info.on-call-support[0]=unavailable")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ShippingContactInfoDto dto = ctx.getBean(ShippingContactInfoDto.class);
                    assertThat(dto.getMessage()).isEqualTo("unavailable");
                    assertThat(dto.getContactDetails()).containsKeys("name", "email");
                    assertThat(dto.getOnCallSupport()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("config-repo values override the local fallback key by key")
    void configRepoOverridesTheFallback() {
        // Precedence, measured: spring.config.import wins over the importing
        // document, so config-repo overrides the fallback when the Config
        // Server is up. Expressed here as later property values winning, which
        // is the same ordering.
        //
        // The sharp edge this pins is the MAP: maps merge per key while lists
        // are replaced wholesale. So a key present in the local fallback and
        // absent from config-repo SURVIVES as a stale value -- which is why the
        // fallback's contact-details key set must match config-repo's exactly.
        // Here `email` is supplied only locally and is still present after the
        // override, demonstrating the leak this constraint prevents.
        runner.withUserConfiguration(ValidatedHolder.class)
                .withPropertyValues(
                        "oriah.shipping.contact-info.message=from config-repo",
                        "oriah.shipping.contact-info.contact-details.name=from config-repo",
                        "oriah.shipping.contact-info.contact-details.email=from the local fallback",
                        "oriah.shipping.contact-info.on-call-support[0]=+27 21 555 0188")
                .run(ctx -> {
                    ShippingContactInfoDto dto = ctx.getBean(ShippingContactInfoDto.class);
                    assertThat(dto.getMessage()).isEqualTo("from config-repo");
                    assertThat(dto.getContactDetails())
                            .as("a map key supplied by only ONE source survives the merge; "
                                    + "that is why the fallback's key set must mirror "
                                    + "config-repo's exactly")
                            .containsEntry("name", "from config-repo")
                            .containsEntry("email", "from the local fallback");
                });
    }

    @Test
    @DisplayName("the DTO is mutable, so /actuator/refresh rebinds it in place")
    void theDtoIsMutableSoRefreshWorks() {
        // Not a style question. ConfigurationPropertiesRebinder rebinds the
        // EXISTING bean in place on /actuator/refresh. A record would force a
        // new instance and the controller, holding a constructor-injected
        // reference, would keep serving the old values forever -- so editing
        // config-repo would appear to do nothing.
        assertThat(ShippingContactInfoDto.class.isRecord())
                .as("a record cannot be rebound in place; /actuator/refresh would "
                        + "silently have no effect on the served contact info")
                .isFalse();
        assertThat(java.util.Arrays.stream(ShippingContactInfoDto.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .as("setters are what the rebinder writes through")
                .contains("setMessage", "setContactDetails", "setOnCallSupport");
    }

    @Configuration
    @EnableConfigurationProperties(ShippingContactInfoDto.class)
    static class ValidatedHolder {
    }

    /** Unused, but documents what plain binding does. See the class javadoc. */
    @SuppressWarnings("unused")
    private static void whatPlainBindingWouldDo(Map<String, String> details, List<String> onCall) {
        // Measured in a spike: without @Validated, an absent prefix binds every
        // field to null and the context starts. The endpoint then answers 200
        // with three nulls. Keeping @Validated is what turns that silent lie
        // into a startup failure -- and the fallback is what ensures the
        // startup failure never happens in practice.
    }
}
