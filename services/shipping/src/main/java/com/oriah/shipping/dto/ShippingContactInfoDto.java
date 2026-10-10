package com.oriah.shipping.dto;

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
 * Operator contact details for the shipping service, served by
 * {@code GET /api/v1/shipping/contact-info} and bound from
 * {@code oriah.shipping.contact-info.*}.
 *
 * <p>This is the course's {@code AccountsContactInfoDto} convention: a service
 * that is misbehaving at 2am should be able to tell you who owns it, from its
 * own API, without anyone finding a wiki page.
 *
 * <h2>Prefix</h2>
 *
 * <p>{@code oriah.shipping.contact-info.*}, not the course's root-level
 * {@code accounts.*}. Three reasons, in order of weight:
 * {@code config-repo/application.yaml} is served to <em>every</em> service, so
 * a root key named after one service sits in a namespace shared with
 * {@code spring}, {@code management}, {@code server}, {@code eureka} and
 * {@code resilience4j}; {@code CatalogProperties} already set the
 * {@code oriah.<svc>.*} precedent, and internal consistency beats course parity
 * when a second convention would make every future knob a coin flip; and
 * owning one vendor segment is the documented Spring convention.
 *
 * <h2>Why mutable, and not a record</h2>
 *
 * <p>{@code ConfigurationPropertiesRebinder} rebinds the <em>existing</em> bean
 * in place when {@code /actuator/refresh} is called, so a controller holding a
 * constructor-injected reference sees the new values. A record would force a
 * new instance and the controller would keep serving the old ones forever —
 * editing config-repo would appear to do nothing. Pinned by a test, because
 * "make it a record, records are better" is an entirely reasonable-looking
 * change that would quietly break refresh.
 *
 * <h2>Why {@code @Validated} is safe here but would not be on its own</h2>
 *
 * <p>Measured with a throwaway spike rather than reasoned about, because this
 * is the Tier 4 defect shape exactly — values that naturally belong in
 * config-repo, consumed through a binding that might be a hard link:
 *
 * <ul>
 *   <li><strong>plain binding, prefix absent</strong> → every field binds
 *       {@code null} and the context starts, so the endpoint answers 200 with
 *       three nulls: a response that lies;</li>
 *   <li><strong>{@code @Validated}, prefix absent</strong> →
 *       {@code BindValidationException} and the context <em>refuses to
 *       start</em>. On its own that would repeat Tier 4's mistake, where
 *       centralizing {@code resilience4j} {@code base-config} turned an
 *       {@code optional:configserver} dependency into a required one.</li>
 * </ul>
 *
 * <p>So {@code @Validated} is paired with a mandatory fallback block in
 * shipping's own {@code application.yaml}. Values are then always present, and
 * a config-repo typo that empties one is caught at startup instead of served.
 *
 * <p>One non-obvious constraint follows from how property sources combine:
 * <strong>maps merge key by key, lists are replaced wholesale.</strong> So a
 * {@code contact-details} key present in the local fallback and absent from
 * config-repo survives as a stale value. The fallback's key set must mirror
 * config-repo's exactly — also pinned by a test.
 */
@ConfigurationProperties(prefix = "oriah.shipping.contact-info")
@Validated
@Getter
@Setter
@Schema(name = "ShippingContactInfo",
        description = "Who owns the shipping service, and how to reach them")
public class ShippingContactInfoDto {

    @NotBlank
    @Schema(description = "Human-readable note about what this service is responsible for",
            example = "Oriah shipping service - contact for rate or carrier issues")
    private String message;

    @NotEmpty
    @Schema(description = "Owning team's contact channels, keyed by channel name")
    private Map<String, String> contactDetails;

    @NotEmpty
    @Schema(description = "Phone numbers to try, in order, outside office hours")
    private List<String> onCallSupport;
}
