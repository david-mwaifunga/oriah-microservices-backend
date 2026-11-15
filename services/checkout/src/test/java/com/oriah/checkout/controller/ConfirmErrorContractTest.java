package com.oriah.checkout.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.exception.CartEmptyException;
import com.oriah.checkout.exception.DownstreamRejectedException;
import com.oriah.checkout.exception.DownstreamUnavailableException;
import com.oriah.checkout.exception.RefusalPassedOnException;
import com.oriah.checkout.service.CheckoutService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP status and problem body each checkout failure produces.
 *
 * <h2>Why this exists alongside {@code ConfirmFailurePathsTest}</h2>
 *
 * <p>That test proves the saga <em>throws</em> the right exception. It cannot
 * prove the status a caller actually receives — an exception nothing maps still
 * reaches the client as a bare 500, and the status is the only part of this
 * contract the storefront branches on. So the two halves are pinned separately:
 * the saga's decisions there, the wire contract here.
 *
 * <p>It is also where the statuses get checked for being <em>usable</em>, not
 * merely non-200: 503 must carry {@code Retry-After} for the retry it invites
 * to be well-behaved, a refusal of order's or shipping's that is passed on must
 * keep its own type and its extras (the {@code shortages}), and the 500s must
 * not echo internal topology back to a shopper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class ConfirmErrorContractTest {

    private static final String PROBLEM_BASE = "https://oriah.dev/problems/";

    /**
     * A valid body: validation fires before the handler, so this must pass it.
     *
     * <p>deliveryOption and quoteDigest are REQUIRED, and omitting either turns
     * every test in this class into a 400 -- a red bar that reads as a
     * status-mapping regression when the real cause is a stale fixture. The
     * digest's value is irrelevant here because CheckoutService is mocked;
     * these tests are about the controller and the advice, not about pricing.
     */
    private static final String VALID_BODY = """
            {"shippingAddressId":"11111111-1111-1111-1111-111111111111",
             "deliveryOption":"SELF_COLLECT",
             "quoteDigest":"0000000000000000000000000000000000000000000000000000000000000000"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CheckoutService service;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an empty cart is 409 application/problem+json, not 200")
    void emptyCartIsConflict() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());

        confirm().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "cart-empty"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    // ------------------------------------------- what confirm accepts (item 2, 4.4)

    private org.springframework.test.web.servlet.ResultActions confirmWith(String body) throws Exception {
        return mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions previewWith(String body) throws Exception {
        return mvc.perform(post("/api/v1/checkout/preview")
                .with(authenticated())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static final String DIGEST = "\"quoteDigest\":\"" + "0".repeat(64) + "\"";
    private static final String ADDRESS = "\"shippingAddressId\":\"11111111-1111-1111-1111-111111111111\"";
    private static final String TOWN = "\"deliveryTownId\":\"55555555-5555-5555-5555-555555555555\"";
    /** Who the parcel is for, typed the way a Zambian types a phone number. */
    private static final String CONTACT = "\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"097 123 4567\"";

    @Test
    @DisplayName("the delivery option is required: confirm places the option the shopper named")
    void theDeliveryOptionIsRequired() throws Exception {
        confirmWith("{" + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a delivery option that is not an option code is a 400")
    void aMalformedDeliveryOptionIsRefused() throws Exception {
        confirmWith("{\"deliveryOption\":\"self collect\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a quote digest that is not 64 lower-case hex characters is a 400")
    void aMalformedQuoteDigestIsRefused() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\","
                + "\"quoteDigest\":\"any-non-blank-token\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("quoteDigest")));
    }

    @Test
    @DisplayName("self-collect needs no address")
    void selfCollectNeedsNoAddress() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\"," + DIGEST + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("any other option still needs somewhere to deliver to")
    void aDeliveryNeedsAnAddress() throws Exception {
        confirmWith("{\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + "," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("shippingAddressId")));
    }

    @Test
    @DisplayName("a customer cannot name a delivery amount, a shipping amount or a total, whatever the value (even 0): the charge is always the shop's published town price, never "
            + "a figure from the customer (Phase 9: an agreed delivery enters only through staff)")
    void aCustomerCannotNameWhatDeliveryCosts() throws Exception {
        for (String field : new String[] {"deliveryAmount", "shippingAmount", "total"}) {
            for (String value : new String[] {"0", "0.00", "1.00", "150.00"}) {
                confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + "," + CONTACT + "," + DIGEST + ",\"" + field + "\":" + value + "}")
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem(field)));
            }
        }
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\"," + DIGEST + ",\"deliveryAmount\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryAmount")));
    }

    @Test
    @DisplayName("a delivered option without a delivery town is a 400 naming deliveryTownId")
    void aDeliveryNeedsATown() throws Exception {
        // Delivery is priced per town, so a delivered quote must name its
        // town. The address is given here, so the town is the one thing
        // missing and the only field the shopper is told to fix.
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "validation"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryTownId")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("shippingAddressId"))));
    }

    @Test
    @DisplayName("self-collect with a delivery town is a 400: collecting is not delivered to a town")
    void selfCollectTakesNoTown() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\"," + TOWN + "," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryTownId")));
    }

    @Test
    @DisplayName("self-collect with a courier payer is a 400: there is no courier to pay")
    void selfCollectTakesNoPayer() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\",\"payer\":\"ORIAH_COLLECTS\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("payer")));
    }

    @Test
    @DisplayName("a payer that is not one of the two couriers' payers is a 400")
    void aBadPayerIsRefused() throws Exception {
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + ",\"payer\":\"EVERYONE\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("payer")));
    }

    @Test
    @DisplayName("a delivered choice with an address, a town and a payer is accepted")
    void aWholeDeliveredChoiceIsAccepted() throws Exception {
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + ",\"payer\":\"RECIPIENT_PAYS\"," + CONTACT + "," + DIGEST + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a delivery needs someone to hand the parcel to: no recipient name and no phone are each a field error, naming the field to fill in")
    void aDeliveryNeedsARecipient() throws Exception {
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + "," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "validation"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItems("recipientName", "recipientPhone")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("shippingAddressId"))))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("deliveryTownId"))));
    }

    @Test
    @DisplayName("a phone number that cannot be read is a 400 on recipientPhone, in plain words, and the name is not blamed")
    void aBadPhoneIsRefused() throws Exception {
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + ",\"recipientName\":\"Mulenga Chanda\",\"recipientPhone\":\"12345\","
                + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("recipientPhone")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("recipientName"))))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='recipientPhone')].message").value(Matchers.hasItem(Matchers.containsString("phone"))));
    }

    @Test
    @DisplayName("a name that is not a name is a 400 on recipientName, and the phone is not blamed")
    void aBadNameIsRefused() throws Exception {
        confirmWith("{" + ADDRESS + ",\"deliveryOption\":\"OWN_DELIVERY\"," + TOWN + ",\"recipientName\":\"12345\",\"recipientPhone\":\"0971234567\","
                + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("recipientName")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("recipientPhone"))));
    }

    @Test
    @DisplayName("self-collect takes no recipient: the shopper collects, so a name or a phone sent with it is a 400 on that field")
    void selfCollectTakesNoRecipient() throws Exception {
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\"," + CONTACT + "," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItems("recipientName", "recipientPhone")));
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\",\"recipientName\":\"Mulenga Chanda\"," + DIGEST + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("recipientName")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.not(Matchers.hasItem("recipientPhone"))));
        confirmWith("{\"deliveryOption\":\"SELF_COLLECT\",\"recipientName\":\"\",\"recipientPhone\":\"  \"," + DIGEST + "}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a preview for an option that is not an option code is a 400")
    void aMalformedPreviewOptionIsRefused() throws Exception {
        previewWith("{\"deliveryOption\":\"self collect\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryOption")));
    }

    @Test
    @DisplayName("a preview for a delivered option without a town is a 400 naming deliveryTownId")
    void aPreviewOfADeliveryNeedsATown() throws Exception {
        previewWith("{\"deliveryOption\":\"OWN_DELIVERY\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.hasItem("deliveryTownId")));
    }

    @Test
    @DisplayName("a preview needs no delivery choice yet: the bag is priced and no total is quoted")
    void aPreviewNeedsNoDeliveryChoice() throws Exception {
        previewWith("{}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a downstream outage is 503 WITH Retry-After, so the retry it invites is well-behaved")
    void downstreamOutageIsRetryableServiceUnavailable() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new DownstreamUnavailableException("order", new RuntimeException("refused")));

        confirm().andExpect(status().isServiceUnavailable())
                // The header is the point of choosing 503. Without it a client
                // either gives up or hammers. The gateway never replays a POST,
                // so the retry is the client's, and it is only safe with the SAME key.
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "service-unavailable"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("the 503 words are true: checkout takes no money, and a lost reply may hide a placed order, so the retry is told to keep its key")
    void theOutageDetailTellsTheTruth() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new DownstreamUnavailableException("order", new RuntimeException("reset")));

        confirm().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(Matchers.allOf(
                        Matchers.not(Matchers.containsStringIgnoringCase("charged")),
                        Matchers.containsString("Idempotency-Key"))));
    }

    // ------------------------------------------- order's and shipping's refusals, passed on

    @Test
    @DisplayName("order's insufficient-stock is 409 under its own type, with the same shortages")
    void insufficientStockIsConflictNamingTheShortLines() throws Exception {
        UUID goods = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID box = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(service.confirm(any(), any())).thenThrow(new RefusalPassedOnException(
                HttpStatus.CONFLICT, "insufficient-stock", "not everything on the order can be held: 2 line(s) short",
                Map.<String, Object>of("shortages", List.of(
                        Map.<String, Object>of("variantId", goods, "kind", "GOODS", "requested", 2, "available", 1),
                        Map.<String, Object>of("variantId", box, "kind", "BOX", "requested", 1, "available", 0)))));

        confirm().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // The type is order's own, not checkout's "conflict": the
                // storefront branches on it to say which piece is gone.
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "insufficient-stock"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("not everything on the order can be held: 2 line(s) short"))
                .andExpect(jsonPath("$.shortages", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.shortages[0].variantId").value(goods.toString()))
                .andExpect(jsonPath("$.shortages[0].kind").value("GOODS"))
                .andExpect(jsonPath("$.shortages[0].requested").value(2))
                .andExpect(jsonPath("$.shortages[0].available").value(1))
                .andExpect(jsonPath("$.shortages[1].variantId").value(box.toString()))
                .andExpect(jsonPath("$.shortages[1].kind").value("BOX"))
                .andExpect(jsonPath("$.shortages[1].requested").value(1))
                .andExpect(jsonPath("$.shortages[1].available").value(0));
    }

    @Test
    @DisplayName("order's hold-limit-reached is 409 under its own type")
    void holdLimitReachedIsConflict() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new RefusalPassedOnException(
                HttpStatus.CONFLICT, "hold-limit-reached", null, Map.of()));

        confirm().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "hold-limit-reached"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("shipping's town-not-offered is 409 under its own type")
    void townNotOfferedIsConflict() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new RefusalPassedOnException(
                HttpStatus.CONFLICT, "town-not-offered", "We do not deliver there yet.", Map.of()));

        confirm().andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "town-not-offered"))
                .andExpect(jsonPath("$.detail").value("We do not deliver there yet."));
    }

    @Test
    @DisplayName("a downstream refusing our request is a generic 500 that names no internal service")
    void rejectedRequestDoesNotLeakTopology() throws Exception {
        when(service.confirm(any(), any())).thenThrow(
                new DownstreamRejectedException("order", 400, new RuntimeException("bad payload")));

        confirm().andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // Deliberately the shared catch-all's generic body. The
                // exception message names the service and its status for the
                // log; "the order service rejected ... with 400" is internal
                // topology and a shopper can do nothing with it.
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "internal-server-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value(
                        Matchers.not(Matchers.containsString("order"))));
    }

    @Test
    @DisplayName("an empty answer from order is the same generic 500: final, with no Retry-After")
    void anEmptyOrderAnswerIsAGenericFinal500() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new DownstreamRejectedException("order"));

        confirm().andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "internal-server-error"))
                .andExpect(jsonPath("$.detail").value(
                        Matchers.not(Matchers.containsString("order"))));
    }

    // ------------------------------------------- the 200

    @Test
    @DisplayName("a placed order is a 200 saying placed and held: no payment status, no intent")
    void aPlacedOrderIsOkAndNotPaid() throws Exception {
        UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(service.confirm(any(), any())).thenReturn(new ConfirmResponse(
                orderId, "CREATED", new BigDecimal("100.00"), "ZMW", Instant.parse("2026-11-13T10:10:00Z"),
                "Your order is placed and its pieces are held for you until the time shown."));

        confirm().andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.orderStatus").value("CREATED"))
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.paymentStatus").doesNotExist())
                .andExpect(jsonPath("$.paymentIntentId").doesNotExist());
    }

    // ------------------------------------------- the published description

    @Test
    @DisplayName("the API description of the confirm 409 says nothing was ordered, and names the refusals a storefront branches on")
    void theDocumented409SaysNothingWasOrdered() throws Exception {
        // The storefront's types and the Postman collection are generated from
        // this description, so it must be as true as the problem detail.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/checkout/confirm'].post.responses['409'].description").value(
                        Matchers.allOf(
                                Matchers.containsString("Nothing was ordered"),
                                Matchers.containsString("cart-empty"),
                                Matchers.containsString("pricing-changed"),
                                Matchers.containsString("insufficient-stock"),
                                Matchers.containsString("hold-limit-reached"),
                                Matchers.containsString("town-not-offered"))));
    }

    @Test
    @DisplayName("the API description of the confirm 503 carries Retry-After and says a retry must keep its Idempotency-Key, because the order may already exist")
    void theDocumented503TellsTheClientToKeepItsKey() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/checkout/confirm'].post.responses['503'].description").value(
                        Matchers.allOf(
                                Matchers.containsString("Idempotency-Key"),
                                Matchers.containsString("Retry-After"),
                                Matchers.not(Matchers.containsStringIgnoringCase("nothing was ordered")))));
    }

    @Test
    @DisplayName("confirm no longer documents a 402 or a 502: checkout takes no payment")
    void thereIsNoPaymentStatusInTheDescription() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/checkout/confirm'].post.responses['402']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/checkout/confirm'].post.responses['502']").doesNotExist());
    }

    @Test
    @DisplayName("the Idempotency-Key header is read off the request, not dropped")
    void idempotencyKeyIsReadFromTheHeader() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());

        mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .header("Idempotency-Key", "idem-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));

        org.mockito.Mockito.verify(service).confirm(any(), org.mockito.ArgumentMatchers.eq("idem-123"));
    }

    @Test
    @DisplayName("no Idempotency-Key header reaches the service as null, never a made-up key")
    void anAbsentIdempotencyKeyReachesTheServiceAsNull() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());

        confirm();

        org.mockito.Mockito.verify(service).confirm(any(), org.mockito.ArgumentMatchers.isNull());
    }

    // ------------------------------------------- the Idempotency-Key is judged at the edge

    private org.springframework.test.web.servlet.ResultActions confirmWithKey(String key) throws Exception {
        return mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));
    }

    @Test
    @DisplayName("an Idempotency-Key longer than order's limit of 128 characters is a 400 at the edge, before any downstream call: not a 500 after four or five of them")
    void anOverlongIdempotencyKeyIsRefusedAtTheEdge() throws Exception {
        confirmWithKey("k".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Idempotency-Key")));

        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).confirm(any(), any());
    }

    @Test
    @DisplayName("a key of exactly 128 characters is accepted and reaches the service whole")
    void aKeyOfTheLimitIsAccepted() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new CartEmptyException());
        String key = "k".repeat(128);

        confirmWithKey(key);

        org.mockito.Mockito.verify(service).confirm(any(), org.mockito.ArgumentMatchers.eq(key));
    }

    // ------------------------------------------- who may call, and the typed 409s

    @Test
    @DisplayName("preview and confirm need a token (401) and the USER role (403): the edge is not the only thing standing in front of them")
    void preview_and_confirm_demandTheUserRole() throws Exception {
        for (String path : List.of("/api/v1/checkout/preview", "/api/v1/checkout/confirm")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")))
                    .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a mixed-currency bag is a 409 under its own type, not an untyped conflict the storefront would read as 'your bag is empty'")
    void aMixedCurrencyBagHasItsOwnType() throws Exception {
        when(service.confirm(any(), any())).thenThrow(new com.oriah.checkout.exception.MixedCurrencyException("ZMW", "USD"));

        confirm().andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "mixed-currency"));
    }

    @Test
    @DisplayName("pricing-changed names the lines it concerns, and bag-not-purchasable the lines that cannot be sold: the storefront marks those rows")
    void theTypedConflictsNameTheirLines() throws Exception {
        UUID one = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID two = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(service.confirm(any(), any())).thenThrow(new com.oriah.checkout.exception.PricingChangedException(List.of(one, two)));
        confirm().andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "pricing-changed"))
                .andExpect(jsonPath("$.changedLines[0]").value(one.toString()))
                .andExpect(jsonPath("$.changedLines[1]").value(two.toString()));

        // doThrow, not when(...): the first stub already throws, and when(service.confirm(...)) would CALL it while setting up the second.
        org.mockito.Mockito.doThrow(new com.oriah.checkout.exception.BagNotPurchasableException(List.of(two))).when(service).confirm(any(), any());
        confirm().andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "bag-not-purchasable"))
                .andExpect(jsonPath("$.unavailableLines[0]").value(two.toString()));
    }

    private org.springframework.test.web.servlet.ResultActions confirm() throws Exception {
        return mvc.perform(post("/api/v1/checkout/confirm")
                .with(authenticated())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));
    }

    private static RequestPostProcessor authenticated() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
