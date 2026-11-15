package com.oriah.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.oriah.order.client.InventoryClient;
import com.oriah.order.client.ShippingClient;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * What the services order calls say, in the shapes they say it, for every test that places an order. A placement asks shipping for the
 * delivery price and inventory to HOLD the pieces; a test that places an order must answer both, and says here how.
 */
public final class SaleStubs {

    /** The box inventory says goes with every piece in these tests. */
    public static final UUID BOX_VARIANT = UUID.fromString("b0b0b0b0-0000-4000-8000-000000000001");

    private SaleStubs() {
    }

    /** Shipping answering every ask as SELF_COLLECT at no charge, in the currency asked. */
    public static void shippingSelfCollect(ShippingClient shipping) {
        doAnswer(inv -> {
            ShippingClient.QuoteAsk asked = inv.getArgument(0);
            return new ShippingClient.QuoteReply(null, null, "SELF_COLLECT", null, new BigDecimal("0.00"), asked.currency(), null);
        }).when(shipping).quote(any());
    }

    /** Shipping answering every ask with this one delivery: the option, town and payer it names, at this amount. */
    public static void shippingDelivers(ShippingClient shipping, String option, UUID town, String payer, String amount, String currency) {
        doAnswer(inv -> new ShippingClient.QuoteReply(town, "Kabwe", option, "Oriah delivery", payer, new BigDecimal(amount), currency, UUID.randomUUID()))
                .when(shipping).quote(any());
    }

    /** Shipping's list of where Oriah can deliver and by what, with no price: this one town (in Copperbelt) and the four seeded options. */
    public static void shippingPlaces(ShippingClient shipping, UUID town) {
        doReturn(new ShippingClient.Places(List.of(new ShippingClient.Places.Province(UUID.randomUUID(), "Central", List.of(new ShippingClient.Places.Town(town, "Kabwe")))),
                List.of(new ShippingClient.Places.Option("OWN_DELIVERY", "Oriah delivery", "OWN_DELIVERY"), new ShippingClient.Places.Option("YANGO", "Yango", "COURIER"),
                        new ShippingClient.Places.Option("PLATINUM", "Platinum", "COURIER"), new ShippingClient.Places.Option("EMS", "EMS", "COURIER"))))
                .when(shipping).places();
    }

    /** Shipping having no published price for what is asked: 409 town-not-offered, as it answers for a town nobody has priced. */
    public static void shippingNotOffered(ShippingClient shipping) {
        doThrow(feign(409, "{\"type\":\"https://oriah.dev/problems/town-not-offered\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"not offered\"}")).when(shipping).quote(any());
    }

    /** Inventory holding whatever it is asked to hold, for ten minutes from now, with one box (BOX_VARIANT) per piece. */
    public static void inventoryHolds(InventoryClient inventory) {
        doAnswer(inv -> held(inv.getArgument(0), Instant.now().plusSeconds(600))).when(inventory).reserve(any());
    }

    /** Inventory selling whatever hold it is asked to sell (CONSUMED), and giving back any it is asked to release (RELEASED). */
    public static void inventorySells(InventoryClient inventory) {
        doAnswer(inv -> new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "CONSUMED", Instant.now(), Instant.now(), Instant.now(), List.of()))
                .when(inventory).consume(any(), any());
        doAnswer(inv -> new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "RELEASED", Instant.now(), Instant.now(), Instant.now(), List.of()))
                .when(inventory).release(any());
    }

    /** Inventory swapping whatever box it is asked to swap: the swap as asked, dated to the day of the sale. */
    public static void inventorySwaps(InventoryClient inventory) {
        doAnswer(inv -> {
            InventoryClient.SwapAsk ask = inv.getArgument(0);
            return new InventoryClient.BoxSwap(ask.amendmentId(), ask.orderId(), ask.productId(), ask.originalSaleLineId(), ask.replacementSaleLineId(),
                    ask.replacementBoxVariantId(), 1, Instant.parse("2026-11-12T08:00:00Z"), Instant.now(), ask.reason(), false);
        }).when(inventory).swapBox(any());
    }

    /** Inventory stretching any hold to the time asked (it caps at a day itself; these tests ask for what they expect back). */
    public static void inventoryExtends(InventoryClient inventory) {
        doAnswer(inv -> new InventoryClient.Reservation(inv.getArgument(0), UUID.randomUUID(), "HELD", Instant.now(),
                ((InventoryClient.ExtendAsk) inv.getArgument(1)).until(), null, List.of()))
                .when(inventory).extend(any(), any());
    }

    /** The HELD reply for this ask: every line as asked and one box line per goods line, under the line's own box-line id. */
    public static InventoryClient.Reservation held(InventoryClient.ReserveAsk ask, Instant expiresAt) {
        List<InventoryClient.HeldLine> lines = new ArrayList<>();
        for (InventoryClient.LineAsk l : ask.lines()) {
            lines.add(new InventoryClient.HeldLine(l.saleLineId(), l.variantId(), "GOODS", l.productId(), l.quantity(), 0, l.quantity()));
            lines.add(new InventoryClient.HeldLine(l.boxLineId(), BOX_VARIANT, "BOX", l.productId(), l.quantity(), 0, l.quantity()));
        }
        return new InventoryClient.Reservation(ask.reservationId(), ask.orderId(), "HELD", Instant.now(), expiresAt, null, lines);
    }

    /** Inventory refusing with this problem (a 409 whose body names the type, the detail and any extra properties as JSON). */
    public static void inventoryRefuses(InventoryClient inventory, int status, String problemJson) {
        doThrow(feign(status, problemJson)).when(inventory).reserve(any());
    }

    /** A FeignException as the client raises it for an answer with this status and body. */
    public static FeignException feign(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, new RequestTemplate());
        return FeignException.errorStatus("Client#call()", feign.Response.builder().status(status).reason("x").request(request)
                .headers(Map.of()).body(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)).build());
    }

    /** The problem body inventory sends for a shortage of one line. */
    public static String shortage(UUID variantId, String kind, int requested, int available) {
        return """
                {"type":"https://oriah.dev/problems/insufficient-stock","title":"Conflict","status":409,
                 "detail":"not everything on the order can be held: 1 line(s) short",
                 "shortages":[{"variantId":"%s","kind":"%s","requested":%d,"available":%d}]}""".formatted(variantId, kind, requested, available);
    }
}
