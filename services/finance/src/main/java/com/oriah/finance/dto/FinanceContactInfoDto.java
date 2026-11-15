package com.oriah.finance.dto;

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
 * Operator contact details for the finance service, served by {@code GET /api/v1/admin/finance/contact-info} and bound from {@code oriah.finance.contact-info.*}.
 *
 * <p>Mutable, not a record: the configuration rebinder rebinds the existing bean in place on {@code /actuator/refresh}, so the controller sees new values.
 * {@code @Validated} is safe only because this service's own {@code application.yaml} carries a fallback block whose {@code contact-details} key set mirrors
 * config-repo's exactly (maps merge key by key across property sources; lists are replaced wholesale).
 *
 * @see com.oriah.finance.controller.ContactInfoController
 */
@ConfigurationProperties(prefix = "oriah.finance.contact-info")
@Validated
@Getter
@Setter
@Schema(name = "FinanceContactInfo", description = "Who owns the finance service, and how to reach them")
public class FinanceContactInfoDto {

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
