package com.oriah.checkout.dto;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * Operator contact details for the checkout service, served by
 * {@code GET /api/v1/checkout/contact-info} and bound from
 * {@code oriah.checkout.contact-info.*}.
 *
 * <p>The course's {@code AccountsContactInfoDto} convention: a service that is
 * misbehaving at 2am should be able to say who owns it, from its own API,
 * without anyone having to find a wiki page.
 *
 * <p>Prefix is {@code oriah.<svc>.*} rather than the course's root-level
 * {@code accounts.*}, because {@code config-repo/application.yaml} is served
 * to <em>every</em> service and a root key named after one of them would sit in
 * the namespace shared with {@code spring}, {@code management} and
 * {@code resilience4j}. {@code CatalogProperties} set that precedent.
 *
 * <p>Mutable, not a record: {@code ConfigurationPropertiesRebinder} rebinds the
 * existing bean in place on {@code /actuator/refresh}, so a controller holding
 * a constructor-injected reference sees new values. A record would force a new
 * instance and the controller would serve the old ones forever.
 *
 * <p>{@code @Validated} is safe only because this service's own
 * {@code application.yaml} carries a fallback block. Measured with a spike:
 * with the prefix absent, plain binding yields three nulls and a 200 that lies,
 * while {@code @Validated} fails the context outright -- which on its own
 * would repeat the Tier 4 {@code base-config} hard-link mistake, where
 * centralizing a value turned an optional dependency into a required one.
 *
 * <p>Maps merge key by key across property sources while lists are replaced
 * wholesale, so the fallback's {@code contact-details} key set must mirror
 * config-repo's exactly or a local-only key survives an override as a stale
 * value.
 *
 * @see com.oriah.checkout.controller.ContactInfoController
 */
@ConfigurationProperties(prefix = "oriah.checkout.contact-info")
@Validated
@Getter
@Setter
@Schema(name = "CheckoutContactInfo",
        description = "Who owns the checkout service, and how to reach them")
public class CheckoutContactInfoDto {

    @NotBlank
    @Schema(description = "What this service is responsible for")
    private String message;

    @NotEmpty
    @Schema(description = "Owning team's contact channels, keyed by channel name")
    private Map<String, String> contactDetails;

    @NotEmpty
    @Schema(description = "Phone numbers to try, in order, outside office hours")
    private List<String> onCallSupport;
}
