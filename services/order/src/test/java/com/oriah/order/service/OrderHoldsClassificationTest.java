package com.oriah.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.order.SaleStubs;
import com.oriah.order.client.InventoryClient;
import com.oriah.order.exception.UpstreamUnavailableException;
import com.oriah.order.model.Order;
import com.oriah.order.service.OrderHolds.BoxGoneException;
import com.oriah.order.service.OrderHolds.HoldGoneException;
import com.oriah.order.service.OrderHolds.PieceGoneException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * What order does with each way inventory can answer when it is asked to take a NEW hold for money already confirmed ({@code holdAgain}) or to SELL one
 * ({@code sell}). The choice is the money's: a piece that is gone ends the sale and holds the customer's money; a box that is gone only makes it wait; anything
 * that clears by itself is asked again. Each answer is pinned here on its own, because a wrong one either refunds a sale that could have been made or retries
 * for ever one that cannot.
 */
class OrderHoldsClassificationTest {

    private static final UUID RESERVATION = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID RING = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID BOX = UUID.fromString("b0b0b0b0-0000-4000-8000-000000000001");

    private InventoryClient inventory;
    private OrderHolds holds;
    private Order order;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryClient.class);
        holds = new OrderHolds(inventory, Clock.systemUTC(), Duration.ofHours(24));
        order = mock(Order.class);
    }

    private static String problem(String type, String extra) {
        return "{\"type\":\"https://oriah.dev/problems/" + type + "\",\"status\":409,\"detail\":\"x\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private static String shortages(String... lines) {
        return "\"shortages\":[" + String.join(",", lines) + "]";
    }

    private static String line(UUID variant, String kind) {
        return "{\"variantId\":\"" + variant + "\",\"kind\":\"" + kind + "\",\"requested\":1,\"available\":0}";
    }

    private void reserveAnswers(int status, String body) {
        doThrow(SaleStubs.feign(status, body)).when(inventory).reserve(any());
    }

    private void consumeAnswers(int status, String body) {
        doThrow(SaleStubs.feign(status, body)).when(inventory).consume(any(), any());
    }

    // ------------------------------------------------------------------ a new hold

    @Test
    @DisplayName("a shortage of the piece AND its box is the PIECE being gone: the sale ends and the money is held for the customer")
    void aPieceAndABoxShortIsThePiece() {
        reserveAnswers(409, problem("insufficient-stock", shortages(line(RING, "GOODS"), line(BOX, "BOX"))));

        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(PieceGoneException.class);
    }

    @Test
    @DisplayName("a shortage of only the BOX makes the sale wait: a paid ring that is still free is never refunded for a missing box")
    void onlyABoxShortWaits() {
        reserveAnswers(409, problem("insufficient-stock", shortages(line(BOX, "BOX"))));

        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(BoxGoneException.class);
    }

    @Test
    @DisplayName("an insufficient-stock that names no line is treated as the piece being gone, never as a wait for ever")
    void aShortageThatNamesNothingIsThePiece() {
        reserveAnswers(409, problem("insufficient-stock", null));

        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(PieceGoneException.class);
    }

    @Test
    @DisplayName("a product no longer for sale is the piece being gone; a product with no default box is a box that is gone")
    void notSellableAndNoBox() {
        reserveAnswers(409, problem("not-a-sellable-product", null));
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(PieceGoneException.class);

        reserveAnswers(409, problem("no-default-box", null));
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(BoxGoneException.class);
    }

    @Test
    @DisplayName("a conflict that clears by itself (inventory is mid-way through this very hold), a 5xx, another 4xx and a reply with nothing in it are all asked again later")
    void everythingElseIsAskedAgain() {
        reserveAnswers(409, problem("reservation-in-progress", null));
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(UpstreamUnavailableException.class);

        reserveAnswers(503, null);
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(UpstreamUnavailableException.class);

        reserveAnswers(422, "{\"type\":\"https://oriah.dev/problems/validation\",\"status\":422}");
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(UpstreamUnavailableException.class);

        org.mockito.Mockito.reset(inventory);
        doReturn(null).when(inventory).reserve(any());
        assertThatThrownBy(() -> holds.holdAgain(order, RESERVATION)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("what inventory says the new hold is comes back to the caller, whatever it is: a hold already EXPIRED is not mistaken for one that is held")
    void theHoldComesBackAsItIs() {
        InventoryClient.Reservation expired = new InventoryClient.Reservation(RESERVATION, UUID.randomUUID(), "EXPIRED", Instant.now(), Instant.now(), Instant.now(), List.of());
        doReturn(expired).when(inventory).reserve(any());

        assertThat(holds.holdAgain(order, RESERVATION).status()).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------------ the sale itself

    @Test
    @DisplayName("a sale answered with anything but CONSUMED is not a sale: it is asked again, never recorded as paid")
    void aReplyThatIsNotConsumedIsNotASale() {
        doReturn(new InventoryClient.Reservation(RESERVATION, UUID.randomUUID(), "HELD", Instant.now(), Instant.now(), null, List.of()))
                .when(inventory).consume(any(), any());

        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("a hold inventory does not know (404), has released or has let lapse is a hold that is GONE: a new one must be taken")
    void aHoldThatIsNotThereIsGone() {
        consumeAnswers(404, null);
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(HoldGoneException.class);

        consumeAnswers(409, problem("reservation-not-held", null));
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(HoldGoneException.class);

        consumeAnswers(409, problem("reservation-expired", null));
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(HoldGoneException.class);
    }

    @Test
    @DisplayName("a sale refused for a conflict that clears by itself, or answered 5xx or with another 4xx, is asked again: the hold is NOT declared gone")
    void aConflictThatClearsIsNotAGoneHold() {
        consumeAnswers(409, problem("reservation-in-progress", null));
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(UpstreamUnavailableException.class);

        consumeAnswers(503, null);
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(UpstreamUnavailableException.class);

        consumeAnswers(422, "{\"type\":\"https://oriah.dev/problems/validation\",\"status\":422}");
        assertThatThrownBy(() -> holds.sell(RESERVATION, Instant.now())).isInstanceOf(UpstreamUnavailableException.class);
    }
}
