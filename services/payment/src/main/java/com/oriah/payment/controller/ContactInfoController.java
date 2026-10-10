package com.oriah.payment.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.payment.dto.PaymentContactInfoDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/v1/payments/contact-info} -- who owns the payment service.
 *
 * <p>The course's {@code /contact-info} convention, in a controller of its own
 * rather than bolted onto a domain controller. Two reasons: the domain
 * controllers here are mounted at paths like {@code /api/v1/carts/me}, where a
 * nested {@code /contact-info} would read oddly; and an operator endpoint has
 * nothing to do with the domain, so giving it its own file keeps both readable.
 *
 * <h2>Why this path</h2>
 *
 * <p>{@code /api/v1/payments/contact-info} sits <strong>inside the prefix the gateway
 * already routes</strong> to this service ({@code /api/v1/payments/**}), so it is
 * reachable at the edge with no new gateway route and no change to
 * {@code RouteSnapshotTest}'s 29-route snapshot. Mounting it at a
 * service-named path instead would have needed a route per service, and leaving
 * it off the gateway entirely would have published it in
 * {@code /v3/api-docs/payment} -- so the Swagger UI would list an endpoint that
 * 404s at the edge.
 *
 * <p>Authenticated, unlike the course's open version. The details include
 * on-call phone numbers, which should not be readable anonymously from a public
 * storefront edge.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "contact-info", description = "Who owns this service")
public class ContactInfoController {

    private final PaymentContactInfoDto contactInfo;

    public ContactInfoController(PaymentContactInfoDto contactInfo) {
        this.contactInfo = contactInfo;
    }

    /**
     * Returns the {@code @ConfigurationProperties} bean directly, so editing
     * {@code config-repo/payment.yaml} and calling
     * {@code POST /actuator/refresh} changes the response without a redeploy.
     *
     * <p>{@code operationId} is pinned explicitly. springdoc would generate
     * {@code contactInfo} from the method name, which is fine until a second
     * method on any controller in this service happens to share it and
     * springdoc starts appending {@code _1} -- and which handler receives the
     * suffix is decided by registration order, not source order. Pinning it
     * makes the generated client name stable by construction.
     */
    @GetMapping("/contact-info")
    @Operation(operationId = "fetchPaymentContactInfo",
            summary = "Who owns the payment service, and how to reach them")
    public PaymentContactInfoDto contactInfo() {
        return contactInfo;
    }
}
