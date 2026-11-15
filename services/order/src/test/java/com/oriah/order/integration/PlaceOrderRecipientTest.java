package com.oriah.order.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.oriah.order.QuoteFixtures;
import com.oriah.order.SaleStubs;
import com.oriah.order.client.CustomerClient;
import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who a delivered parcel is for and where it goes, kept on the order as it was when it was placed (Phase 8, slice 8.3).
 *
 * <p>The packer or the driver needs a name to ask for, a phone number to ring and an address to go to, and none of it may be looked up later: a shopper can
 * delete an address, and a packer cannot read the customer's book. So order copies it at placement, from what the shopper typed (the recipient) and from the
 * address customer answered with (the lines, read with the shopper's own token), together with the names of the option and the town shipping priced. A
 * pickup has no recipient and nowhere to go, so it keeps none of it, whatever was sent.
 *
 * <p>None of it is ever in an event: the outbox is append-only, and a phone number put there could never be erased.
 */
class PlaceOrderRecipientTest extends SaleTestBase {

    private static final UUID TOWN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String OTHER_ADDRESS = "aaaaaaaa-2222-3333-4444-555555555555";
    private static final String NAME = "Mulenga Chanda";
    private static final String PHONE_TYPED = "097 123 4567";
    private static final String PHONE = "+260971234567";

    private void theShopperHasAnAddressBook() {
        doReturn(List.of(
                new CustomerClient.Address(UUID.fromString(ADDRESS), "Plot 12 Great East Road", "Rhodes Park", "Lusaka", "Lusaka Province", "10101", "ZM"),
                new CustomerClient.Address(UUID.fromString(OTHER_ADDRESS), "Stand 7 Freedom Way", null, "Kabwe", null, "30101", "ZM")))
                .when(customer).myAddresses();
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "ORIAH_COLLECTS", "125.00", "ZMW");
    }

    private static String deliveredDigest(int quantity) {
        return QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, quantity, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", "OWN_DELIVERY",
                new BigDecimal("125.00"), TOWN, "ORIAH_COLLECTS"));
    }

    private static String body(String address, int quantity, String name, String phone) {
        return "{\"shippingAddressId\":\"" + address + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + TOWN + "\","
                + "\"payer\":\"ORIAH_COLLECTS\","
                + (name == null ? "" : "\"recipientName\":\"" + name + "\",")
                + (phone == null ? "" : "\"recipientPhone\":\"" + phone + "\",")
                + "\"quoteDigest\":\"" + deliveredDigest(quantity) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":" + quantity + ",\"unitPrice\":4500.00}]}";
    }

    private MvcResult place(String body, String key) throws Exception {
        var request = post("/api/v1/orders").with(alice()).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key)).andReturn();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private JsonNode problem(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ what is kept

    @Test
    @DisplayName("a delivered order keeps who it is for (name tidied, phone in one spelling), where it goes (the address as customer answered it) and the names of the option and the town")
    void aDeliveredOrderKeepsTheDelivery() throws Exception {
        theShopperHasAnAddressBook();

        MvcResult placed = place(body(ADDRESS, 1, "  Mulenga   Chanda ", PHONE_TYPED), "k1");

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        Map<String, Object> row = row(idOf(placed));
        assertThat(row.get("recipient_name")).isEqualTo(NAME);
        assertThat(row.get("recipient_phone")).isEqualTo(PHONE);
        assertThat(row.get("address_line1")).isEqualTo("Plot 12 Great East Road");
        assertThat(row.get("address_line2")).isEqualTo("Rhodes Park");
        assertThat(row.get("address_city")).isEqualTo("Lusaka");
        assertThat(row.get("address_state")).isEqualTo("Lusaka Province");
        assertThat(row.get("address_postal_code")).isEqualTo("10101");
        assertThat(row.get("address_country")).isEqualTo("ZM");
        assertThat(row.get("delivery_option_name")).isEqualTo("Oriah delivery");
        assertThat(row.get("delivery_town_name")).isEqualTo("Kabwe");
    }

    @Test
    @DisplayName("a pickup keeps no recipient and no address text, even when an address id is sent with it")
    void aPickupKeepsNone() throws Exception {
        theShopperHasAnAddressBook();
        SaleStubs.shippingSelfCollect(shipping);
        String selfCollect = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"quoteDigest\":\""
                + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";

        MvcResult placed = place(selfCollect, null);

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(201);
        Map<String, Object> row = row(idOf(placed));
        for (String column : List.of("recipient_name", "recipient_phone", "address_line1", "address_line2", "address_city", "address_state",
                "address_postal_code", "address_country", "delivery_option_name", "delivery_town_name")) {
            assertThat(row.get(column)).as(column).isNull();
        }
    }

    // ------------------------------------------------------------------ what is asked for

    @Test
    @DisplayName("a delivery needs a recipient: no name and no phone are each a field error, naming the field to fill in; nothing is placed")
    void aDeliveryNeedsARecipient() throws Exception {
        theShopperHasAnAddressBook();

        MvcResult result = place(body(ADDRESS, 1, null, null), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(result).get("fieldErrors").toString()).contains("recipientName").contains("recipientPhone");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("an unreadable phone or a name that is not a name is a 400 on that field, in the shared rule's words")
    void anUnreadableRecipientIsRefused() throws Exception {
        theShopperHasAnAddressBook();

        MvcResult badPhone = place(body(ADDRESS, 1, NAME, "12345"), null);
        MvcResult badName = place(body(ADDRESS, 1, "12345", PHONE_TYPED), null);

        assertThat(badPhone.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(badPhone).get("fieldErrors").toString()).contains("recipientPhone").doesNotContain("recipientName");
        assertThat(badName.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(badName).get("fieldErrors").toString()).contains("recipientName").doesNotContain("recipientPhone");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("a pickup takes no recipient: a name or phone sent with it is a 400")
    void aPickupTakesNoRecipient() throws Exception {
        SaleStubs.shippingSelfCollect(shipping);
        String selfCollect = "{\"currency\":\"ZMW\",\"deliveryOption\":\"SELF_COLLECT\",\"recipientName\":\"" + NAME + "\",\"quoteDigest\":\""
                + QuoteFixtures.selfCollect("ZMW", QuoteFixtures.line(RING, 1, "4500.00")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";

        MvcResult result = place(selfCollect, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(result).get("fieldErrors").toString()).contains("recipientName");
    }

    // ------------------------------------------------------------------ once, however often it is asked

    @Test
    @DisplayName("the same placement again answers the same order, whichever way the phone is spelled, and keeps the first snapshot")
    void aRepeatIsTheSameOrder() throws Exception {
        theShopperHasAnAddressBook();
        UUID first = idOf(place(body(ADDRESS, 1, NAME, PHONE_TYPED), "key-1"));

        MvcResult again = place(body(ADDRESS, 1, NAME, "+260 971 234 567"), "key-1");
        MvcResult newKey = place(body(ADDRESS, 1, " Mulenga Chanda", "0971234567"), "key-2");

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(idOf(again)).isEqualTo(first);
        assertThat(newKey.getResponse().getStatus()).isEqualTo(200);
        assertThat(idOf(newKey)).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(row(first).get("recipient_phone")).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("the same Idempotency-Key for ANOTHER recipient is 409 idempotency-key-reused: a key is not a way to change who a parcel is for")
    void aKeyReusedForAnotherRecipientIsRefused() throws Exception {
        theShopperHasAnAddressBook();
        UUID first = idOf(place(body(ADDRESS, 1, NAME, PHONE_TYPED), "key-1"));

        MvcResult otherPhone = place(body(ADDRESS, 1, NAME, "0961234567"), "key-1");
        MvcResult otherName = place(body(ADDRESS, 1, "Chanda Mulenga", PHONE_TYPED), "key-1");

        for (MvcResult refused : new MvcResult[] {otherPhone, otherName}) {
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(problem(refused).get("type").asText()).endsWith("/idempotency-key-reused");
        }
        assertThat(repository.count()).isEqualTo(1);
        assertThat(row(first).get("recipient_phone")).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("the same live bag under a new key for ANOTHER recipient is 409 live-order-has-another-address: answering the first order would send the parcel to the wrong person")
    void aLiveBagForAnotherRecipientIsRefused() throws Exception {
        theShopperHasAnAddressBook();
        place(body(ADDRESS, 1, NAME, PHONE_TYPED), "key-1");

        MvcResult other = place(body(ADDRESS, 1, "Chanda Mulenga", PHONE_TYPED), "key-2");

        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(problem(other).get("type").asText()).endsWith("/live-order-has-another-address");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Oriah's own delivery is income and is never paid by the recipient: a quote that says otherwise is refused (500, shipping's fault), and nothing is placed")
    void ownDeliveryIsNeverRecipientPaid() throws Exception {
        theShopperHasAnAddressBook();
        SaleStubs.shippingDelivers(shipping, "OWN_DELIVERY", TOWN, "RECIPIENT_PAYS", "0.00", "ZMW");
        String body = "{\"shippingAddressId\":\"" + ADDRESS + "\",\"currency\":\"ZMW\",\"deliveryOption\":\"OWN_DELIVERY\",\"deliveryTownId\":\"" + TOWN
                + "\",\"payer\":\"RECIPIENT_PAYS\",\"recipientName\":\"" + NAME + "\",\"recipientPhone\":\"" + PHONE_TYPED + "\",\"quoteDigest\":\""
                + QuoteDigest.of(new Quote(List.of(new QuoteLine(RING, 1, new BigDecimal("4500.00"), new BigDecimal("0.00"), null)), "ZMW", "OWN_DELIVERY",
                        new BigDecimal("0.00"), TOWN, "RECIPIENT_PAYS")) + "\","
                + "\"items\":[{\"variantId\":\"" + RING + "\",\"title\":\"x\",\"quantity\":1,\"unitPrice\":4500.00}]}";

        MvcResult refused = place(body, null);

        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isEqualTo(500);
        assertThat(repository.count()).isZero();
        assertThatThrownBy(() -> new com.oriah.order.model.Order("kc", UUID.randomUUID(), "ZMW", "OWN_DELIVERY", BigDecimal.ZERO, TOWN, "RECIPIENT_PAYS"))
                .as("the order itself refuses it, whichever channel records it (a phone order, Phase 9)")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own delivery");
    }

    // ------------------------------------------------------------------ never in an event, never in a log

    @Test
    @DisplayName("nothing about the recipient or the address is in any order event: the outbox is append-only, so it could never be erased")
    void noRecipientDataInAnyEvent() throws Exception {
        theShopperHasAnAddressBook();
        UUID id = idOf(place(body(ADDRESS, 1, NAME, PHONE_TYPED), "key-1"));
        confirmed(id, "4625.00");

        List<Map<String, Object>> all = jdbc.queryForList("SELECT payload::text AS payload FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = ?", id.toString());
        assertThat(all).isNotEmpty();
        for (Map<String, Object> event : all) {
            assertThat((String) event.get("payload")).doesNotContain("Mulenga").doesNotContain("971234567").doesNotContain("Great East Road")
                    .doesNotContain("Rhodes Park").doesNotContain("Kabwe").doesNotContain("10101");
        }
    }

    // ------------------------------------------------------------------ what the database holds

    @Test
    @DisplayName("the database holds the rules: a name without a phone, a phone that is not +digits, a blank name and half an address are refused")
    void theDatabaseHoldsItsRules() throws Exception {
        theShopperHasAnAddressBook();
        UUID id = idOf(place(body(ADDRESS, 1, NAME, PHONE_TYPED), null));

        for (String set : List.of("recipient_phone = NULL", "recipient_phone = '0971234567'", "recipient_phone = '+26097'", "recipient_name = '   '",
                "address_city = NULL", "address_line1 = NULL")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE orders SET " + set + " WHERE id = ?", id)).as(set).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(row(id).get("recipient_phone")).isEqualTo(PHONE);
    }
}
