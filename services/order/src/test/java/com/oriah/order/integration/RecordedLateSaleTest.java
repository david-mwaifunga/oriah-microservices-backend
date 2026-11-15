package com.oriah.order.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CatalogAsOfClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A sale written on paper and typed in later (Phase 9, slice 9.9): recorded at its TRUE date, whatever its age, and priced as of that date (the price and the
 * promotion of that day, never today's). The person chooses LATE_PAPER and always says why; past the 72 hours of CLIENT_DECISIONS 8.2 only someone who may
 * back-date a sale can key it. And what comes after: the money, confirmed by staff, pays the order dated to the day it arrived, and an order whose hold lapsed
 * while the caller was on the phone is still paid when its pieces are free.
 */
class RecordedLateSaleTest extends SaleTestBase {

    private static final String PATH = "/api/v1/admin/orders/recorded";
    private static final UUID PRICE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String REASON = "the paper note was found in the shop drawer";

    private static RequestPostProcessor sales() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT")).jwt(j -> j.subject("kc-sam").tokenValue("sam-token"));
    }

    private static RequestPostProcessor owner() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_AGENT"), new SimpleGrantedAuthority("ROLE_BACKDATE_SALE")).jwt(j -> j.subject("kc-maya").tokenValue("maya-token"));
    }

    @BeforeEach
    void stubs() {
        priced("PRICED", "4500.00");
        SaleStubs.shippingPlaces(shipping, UUID.fromString("33333333-3333-4333-8333-333333333333"));
        SaleStubs.shippingNotOffered(shipping);
    }

    private void priced(String status, String price) {
        CatalogAsOfClient.AsOfEntry entry = "PRICED".equals(status)
                ? new CatalogAsOfClient.AsOfEntry(RING, "PRICED", PRODUCT, "Aurelia 18k", "Aurelia 18k / Ring Size M", new BigDecimal(price), "ZMW", PRICE_ID,
                        new BigDecimal("0.00"), null, new BigDecimal(price))
                : new CatalogAsOfClient.AsOfEntry(RING, status, PRODUCT, "Aurelia 18k", "Aurelia 18k / Ring Size M", null, null, null, null, null, null);
        doReturn(new CatalogAsOfClient.AsOfResponse(Instant.now(), List.of(entry))).when(catalogAsOf).priceAsOf(any());
    }

    private static String late(Instant at, String reason, String extra) {
        return "{\"kind\":\"LATE_PAPER\",\"caller\":{\"name\":\"Mulenga Chanda\",\"phone\":\"097 123 4567\"},\"lines\":[{\"variantId\":\"" + RING + "\",\"quantity\":1}],"
                + "\"delivery\":{\"mode\":\"PICKUP\"},\"occurredAt\":\"" + at + "\"" + (reason == null ? "" : ",\"backdateReason\":\"" + reason + "\"") + (extra == null ? "" : extra)
                + ",\"expectedTotal\":4500.00}";
    }

    private MvcResult record(RequestPostProcessor who, String key, String body) throws Exception {
        return mvc.perform(post(PATH).with(who).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key).content(body)).andReturn();
    }

    private JsonNode body(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(r.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ the date

    @Test
    @DisplayName("a paper sale is recorded at its typed date and priced as of that date, with its reason and slip: placed_at is when it was keyed, occurred_at when it happened")
    void aPaperSaleIsDatedAndPricedAtItsOwnDate() throws Exception {
        Instant sold = Instant.now().minus(Duration.ofHours(30)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        JsonNode order = body(record(sales(), "k-late", late(sold, REASON, ",\"slipReference\":\"P-17\"")), 201);

        ArgumentCaptor<CatalogAsOfClient.AsOfAsk> asked = ArgumentCaptor.forClass(CatalogAsOfClient.AsOfAsk.class);
        verify(catalogAsOf).priceAsOf(asked.capture());
        assertThat(asked.getValue().at()).as("priced at the sale's own instant").isEqualTo(sold);
        assertThat(order.get("kind").asText()).isEqualTo("LATE_PAPER");
        assertThat(Instant.parse(order.get("occurredAt").asText())).isEqualTo(sold);
        var row = row(UUID.fromString(order.get("id").asText()));
        assertThat(at(row.get("occurred_at"))).isEqualTo(sold);
        assertThat(at(row.get("placed_at"))).isAfter(sold.plus(Duration.ofHours(29)));
        assertThat(row.get("sale_kind")).isEqualTo("LATE_PAPER");
        assertThat(row.get("backdate_reason")).isEqualTo(REASON);
        assertThat(row.get("slip_reference")).isEqualTo("P-17");
    }

    @Test
    @DisplayName("a paper sale keyed twenty minutes ago is still a paper sale: priced as of its date, not as of now (a promotion that has just ended still applies to it)")
    void aRecentPaperSaleIsPricedAtItsDate() throws Exception {
        Instant sold = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        body(record(sales(), "k-recent", late(sold, REASON, null)), 201);

        ArgumentCaptor<CatalogAsOfClient.AsOfAsk> asked = ArgumentCaptor.forClass(CatalogAsOfClient.AsOfAsk.class);
        verify(catalogAsOf).priceAsOf(asked.capture());
        assertThat(asked.getValue().at()).isEqualTo(sold);
    }

    @Test
    @DisplayName("a live sale is priced as of now and cannot be given a date: a date on a live sale is 400 invalid-occurred-at")
    void aLiveSaleTakesNoDate() throws Exception {
        String live = late(Instant.now().minusSeconds(60), null, null).replace("LATE_PAPER", "LIVE_PHONE");

        JsonNode refused = body(record(sales(), "k-live", live), 400);

        assertThat(refused.get("type").asText()).endsWith("/invalid-occurred-at");
        verify(catalogAsOf, never()).priceAsOf(any());
    }

    @Test
    @DisplayName("a paper sale always says why: no reason, or one under five characters, is 400 backdate-reason-required; and it needs its date: none is 400 invalid-occurred-at")
    void aPaperSaleNeedsItsReasonAndItsDate() throws Exception {
        Instant sold = Instant.now().minus(Duration.ofHours(2));

        assertThat(body(record(sales(), "k-r1", late(sold, null, null)), 400).get("type").asText()).endsWith("/backdate-reason-required");
        assertThat(body(record(sales(), "k-r2", late(sold, "four", null)), 400).get("type").asText()).endsWith("/backdate-reason-required");
        String noDate = late(sold, REASON, null).replace(",\"occurredAt\":\"" + sold + "\"", "");
        assertThat(body(record(sales(), "k-r3", noDate), 400).get("type").asText()).endsWith("/invalid-occurred-at");
        assertThat(body(record(sales(), "k-r4", late(Instant.now().plus(Duration.ofHours(1)), REASON, null)), 400).get("type").asText()).endsWith("/invalid-occurred-at");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("beyond 72 hours only someone who may back-date a sale can key it (403 otherwise, nothing asked of catalog or held), and then with its reason")
    void beyondTheWindowNeedsTheCapability() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(10));

        assertThat(record(sales(), "k-old", late(old, REASON, null)).getResponse().getStatus()).isEqualTo(403);
        verify(catalogAsOf, never()).priceAsOf(any());
        verify(inventory, never()).reserve(any());
        JsonNode ok = body(record(owner(), "k-old2", late(old, REASON, null)), 201);

        assertThat(Instant.parse(ok.get("occurredAt").asText())).isEqualTo(old.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(row(UUID.fromString(ok.get("id").asText())).get("recorded_by")).isEqualTo("user:kc-maya");
    }

    // ------------------------------------------------------------------ the price

    @Test
    @DisplayName("a piece with no price on that date cannot be recorded: 409 no-price-at-date, never priced at zero or at today's price, and nothing is held")
    void noPriceAtTheDate() throws Exception {
        priced("NO_PRICE_AT_DATE", null);

        JsonNode refused = body(record(sales(), "k-np", late(Instant.now().minus(Duration.ofHours(30)), REASON, null)), 409);

        assertThat(refused.get("type").asText()).endsWith("/no-price-at-date");
        assertThat(refused.get("variantIds").get(0).asText()).isEqualTo(RING.toString());
        verify(inventory, never()).reserve(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a piece catalog does not know (or a box) is refused as not purchasable; catalog not answering is 503 with nothing saved, never a guess")
    void unknownAndUnavailable() throws Exception {
        priced("UNKNOWN", null);
        assertThat(record(sales(), "k-u1", late(Instant.now().minus(Duration.ofHours(30)), REASON, null)).getResponse().getStatus()).isEqualTo(409);

        doReturn(null).when(catalogAsOf).priceAsOf(any());
        assertThat(record(sales(), "k-u2", late(Instant.now().minus(Duration.ofHours(30)), REASON, null)).getResponse().getStatus()).isEqualTo(409);

        org.mockito.Mockito.doThrow(SaleStubs.feign(503, "{}")).when(catalogAsOf).priceAsOf(any());
        assertThat(record(sales(), "k-u3", late(Instant.now().minus(Duration.ofHours(30)), REASON, null)).getResponse().getStatus()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a paper sale's delivery is a pickup or an agreed amount, never the shop's published price (that is today's price)")
    void aPublishedDeliveryIsTodaysPrice() throws Exception {
        UUID town = UUID.fromString("33333333-3333-4333-8333-333333333333");
        String published = late(Instant.now().minus(Duration.ofHours(30)), REASON, null).replace("\"delivery\":{\"mode\":\"PICKUP\"}",
                "\"delivery\":{\"mode\":\"PUBLISHED\",\"optionCode\":\"YANGO\",\"townId\":\"" + town + "\",\"payer\":\"ORIAH_COLLECTS\"}");

        JsonNode refused = body(record(sales(), "k-pub", published), 409);

        assertThat(refused.get("type").asText()).endsWith("/late-sale-delivery-must-be-agreed");
    }

    // ------------------------------------------------------------------ what comes after

    @Test
    @DisplayName("staff confirm the money and the order is PAID, with a receipt, dated to the day the money ARRIVED; order.paid says channel PHONE, has no owner and nothing personal")
    void theMoneyPaysARecordedOrder() throws Exception {
        // A live sale: the money may come on any day, and the sale counts on the day it was paid (Q22).
        JsonNode recorded = body(record(sales(), "k-pay", late(Instant.now().minus(Duration.ofHours(30)), REASON, null).replace("LATE_PAPER", "LIVE_PHONE")
                .replaceFirst(",\"occurredAt\":\"[^\"]*\"", "").replaceFirst(",\"backdateReason\":\"[^\"]*\"", "")), 201);
        UUID id = UUID.fromString(recorded.get("id").asText());

        confirmed(id, "4500.00");

        var row = row(id);
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat((String) row.get("receipt_number")).matches("OR-\\d{6}");
        assertThat(at(row.get("paid_at"))).isEqualTo(Instant.parse(ARRIVED));
        var paid = events(id, "order.paid");
        assertThat(paid).hasSize(1);
        JsonNode event = payloadOf(paid.get(0));
        assertThat(event.get("channel").asText()).isEqualTo("PHONE");
        assertThat(event.get("keycloakId").isNull()).isTrue();
        assertThat(event.get("occurredAt").asText()).isNotBlank();
        assertThat(event.toString()).doesNotContain("Mulenga").doesNotContain("971234567");
    }

    @Test
    @DisplayName("a paper sale paid on another day than it happened is HELD for the caller as WRONG_DATE, never settled: the sale counts on the day it happened, so a mistyped date "
            + "cannot move its revenue and cost into another month; staff void it and key the money again with the right day")
    void aPaperSalePaidOnAnotherDayIsHeld() throws Exception {
        Instant sold = Instant.parse("2026-11-12T09:00:00Z");
        JsonNode recorded = body(record(owner(), "k-wd", late(sold, REASON, null)), 201);
        UUID id = UUID.fromString(recorded.get("id").asText());

        UUID wrong = confirmed(UUID.randomUUID(), id, "4500.00", "ZMW", "2026-11-14T09:00:00Z");

        assertThat(row(id).get("status")).as("not paid").isEqualTo("CREATED");
        assertThat(settlement(wrong)).containsEntry("state", "UNSETTLED").containsEntry("reason", "WRONG_DATE");
        assertThat(events(id, "order.paid")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_type = 'order' AND event_type = 'order.payment_unsettled' AND payload::text LIKE '%WRONG_DATE%'",
                Integer.class)).isEqualTo(1);

        voided(wrong, id);
        confirmed(UUID.randomUUID(), id, "4500.00", "ZMW", "2026-11-12T13:30:00Z");

        assertThat(row(id).get("status")).as("the same Lusaka day (15:30): paid").isEqualTo("PAID");
        assertThat(at(row(id).get("paid_at"))).isEqualTo(Instant.parse("2026-11-12T13:30:00Z"));
    }

    @Test
    @DisplayName("a live sale is paid whatever day the money arrives: its date follows the money, so it is never held for a date")
    void aLiveSaleFollowsTheMoney() throws Exception {
        JsonNode recorded = body(record(sales(), "k-lv", late(Instant.now(), null, null).replace("LATE_PAPER", "LIVE_PHONE").replaceFirst(",\"occurredAt\":\"[^\"]*\"", "")), 201);
        UUID id = UUID.fromString(recorded.get("id").asText());

        confirmed(UUID.randomUUID(), id, "4500.00", "ZMW", "2026-11-01T09:00:00Z");

        assertThat(row(id).get("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a hold that lapsed while the caller was paying does not lose the sale: money confirmed afterwards pays it when the pieces are free (the box kept), and is held for the caller when they are not")
    void aLapsedHoldIsStillPaid() throws Exception {
        UUID box = UUID.fromString("b0b0b0b0-0000-4000-8000-000000000001");
        String withBox = late(Instant.now(), null, null).replace("LATE_PAPER", "LIVE_PHONE").replaceFirst(",\"occurredAt\":\"[^\"]*\"", "")
                .replace("\"quantity\":1}", "\"quantity\":1,\"boxVariantId\":\"" + box + "\"}");
        UUID id = UUID.fromString(body(record(sales(), "k-lapse", withBox), 201).get("id").asText());
        UUID reservation = (UUID) row(id).get("reservation_id");

        reservationExpired(reservation, id);
        assertThat(row(id).get("status")).isEqualTo("EXPIRED");
        confirmed(id, "4500.00");

        assertThat(row(id).get("status")).as("the pieces were still free").isEqualTo("PAID");
    }
}
