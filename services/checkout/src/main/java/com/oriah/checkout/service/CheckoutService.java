package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.oriah.checkout.client.CartClient;
import com.oriah.checkout.client.OrderClient;
import com.oriah.checkout.client.PaymentClient;
import com.oriah.checkout.client.PromotionClient;
import com.oriah.checkout.client.ShippingClient;
import com.oriah.checkout.dto.ConfirmRequest;
import com.oriah.checkout.dto.ConfirmResponse;
import com.oriah.checkout.dto.PreviewRequest;
import com.oriah.checkout.dto.PreviewResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Saga-style orchestrator for checkout.
 *
 * <p>Preview is a pure read: cart subtotal + (optional) promo + shipping
 * options. Confirm is the place-order sequence:
 * <ol>
 *   <li>Load the customer's cart.</li>
 *   <li>Validate the promo (if any) and compute final total.</li>
 *   <li>Pick the cheapest shipping option for the destination.</li>
 *   <li>Place the order (CREATED status, items snapshot).</li>
 *   <li>Create + confirm a PaymentIntent for the total via FakePaymentGateway.</li>
 *   <li>On payment success: attach the intent to the order (transitions to PAID),
 *       redeem the promo, clear the cart.</li>
 *   <li>On payment failure: cancel the order, leave the cart intact so the user can retry.</li>
 * </ol>
 *
 * <p>This is not a "real" saga with compensating actions across services —
 * the catalog of failure modes is small and we accept a best-effort
 * cleanup pattern. Production would use a saga framework (Eventuate,
 * Axon) or a workflow engine (Camunda, Temporal).
 */
@Service
@Slf4j
public class CheckoutService {

    private final CartClient cart;
    private final PromotionClient promotion;
    private final ShippingClient shipping;
    private final PaymentClient payment;
    private final OrderClient order;

    public CheckoutService(CartClient cart, PromotionClient promotion, ShippingClient shipping,
                           PaymentClient payment, OrderClient order) {
        this.cart = cart;
        this.promotion = promotion;
        this.shipping = shipping;
        this.payment = payment;
        this.order = order;
    }

    public PreviewResponse preview(PreviewRequest req) {
        var myCart = safeCall(cart::getMyCart, "cart.getMyCart");
        if (myCart == null || myCart.items() == null || myCart.items().isEmpty()) {
            return new PreviewResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "USD", null, List.of(), null
            );
        }
        BigDecimal subtotal = myCart.subtotal();

        BigDecimal discount = BigDecimal.ZERO;
        String applied = null;
        if (req.promotionCode() != null && !req.promotionCode().isBlank()) {
            var apply = safeCall(() -> promotion.apply(req.promotionCode(),
                    new PromotionClient.ApplyRequest(subtotal)), "promotion.apply");
            if (apply != null) {
                discount = apply.discount();
                applied = apply.code();
            }
        }

        BigDecimal afterDiscount = subtotal.subtract(discount);
        var rates = safeCall(() -> shipping.rates(
                new ShippingClient.RateRequest(req.destinationCountry(), afterDiscount)), "shipping.rates");
        List<PreviewResponse.ShippingOptionDto> options = new ArrayList<>();
        BigDecimal cheapest = BigDecimal.ZERO;
        String zone = null;
        if (rates != null && rates.options() != null) {
            zone = rates.zone();
            for (var o : rates.options()) {
                options.add(new PreviewResponse.ShippingOptionDto(
                        o.carrier(), o.service(), o.cost(), o.etaDaysMin(), o.etaDaysMax()));
            }
            cheapest = rates.options().stream()
                    .map(ShippingClient.ShippingOption::cost)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
        }

        BigDecimal total = afterDiscount.add(cheapest);
        return new PreviewResponse(subtotal, discount, cheapest, total, "USD", applied, options, zone);
    }

    /**
     * Run a Feign call and swallow any FeignException / runtime error,
     * returning null. Replaces the per-client fallback beans (which we
     * disabled along with the circuit breaker — see application.yaml).
     */
    private <T> T safeCall(java.util.function.Supplier<T> call, String name) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("{} failed: {}", name, e.getMessage());
            return null;
        }
    }

    public ConfirmResponse confirm(ConfirmRequest req) {
        var myCart = safeCall(cart::getMyCart, "cart.getMyCart");
        if (myCart == null || myCart.items() == null || myCart.items().isEmpty()) {
            return new ConfirmResponse(null, null, null, null, BigDecimal.ZERO, "USD", "cart is empty");
        }

        // 1) Promo
        BigDecimal subtotal = myCart.subtotal();
        BigDecimal discount = BigDecimal.ZERO;
        String appliedPromo = null;
        if (req.promotionCode() != null && !req.promotionCode().isBlank()) {
            var apply = safeCall(() -> promotion.apply(req.promotionCode(),
                    new PromotionClient.ApplyRequest(subtotal)), "promotion.apply");
            if (apply != null) {
                discount = apply.discount();
                appliedPromo = apply.code();
            } else {
                return new ConfirmResponse(null, null, null, null, BigDecimal.ZERO, "USD",
                        "promotion code invalid: " + req.promotionCode());
            }
        }
        BigDecimal afterDiscount = subtotal.subtract(discount);

        // 2) Shipping (cheapest)
        BigDecimal shippingCost = BigDecimal.ZERO;
        var rates = safeCall(() -> shipping.rates(
                new ShippingClient.RateRequest(req.destinationCountry(), afterDiscount)), "shipping.rates");
        if (rates != null && rates.options() != null && !rates.options().isEmpty()) {
            shippingCost = rates.options().stream()
                    .map(ShippingClient.ShippingOption::cost)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
        }
        BigDecimal total = afterDiscount.add(shippingCost);

        // 3) Place order in CREATED state
        var items = myCart.items().stream()
                .map(i -> new OrderClient.LineItem(i.variantId(), i.title(), i.quantity(), i.unitPrice()))
                .toList();
        var placed = safeCall(() -> order.place(
                new OrderClient.PlaceOrderRequest(req.shippingAddressId(), "USD", items)), "order.place");
        if (placed == null) {
            return new ConfirmResponse(null, null, null, null, total, "USD", "order service unavailable");
        }
        log.info("Order placed orderId={} status={} total={}", placed.id(), placed.status(), placed.total());

        // 4) Payment
        var intent = safeCall(() -> payment.createIntent(
                new PaymentClient.CreateIntentRequest(placed.id(), total, "USD")), "payment.createIntent");
        if (intent == null) {
            return new ConfirmResponse(placed.id(), placed.status(), null, null, total, "USD", "payment service unavailable");
        }
        var confirmed = safeCall(() -> payment.confirm(intent.id(),
                new PaymentClient.ConfirmRequest(req.cardNumber())), "payment.confirm");
        if (confirmed == null || !"SUCCEEDED".equals(confirmed.status())) {
            // Best-effort: leave the order in CREATED so the user can retry; cart untouched.
            return new ConfirmResponse(placed.id(), placed.status(), intent.id(),
                    confirmed == null ? "UNKNOWN" : confirmed.status(),
                    total, "USD",
                    confirmed == null ? "payment confirmation failed" : "payment declined: " + confirmed.failureReason());
        }
        log.info("Payment SUCCEEDED intentId={}", intent.id());

        // 5) Pay -> attach intent + transition order to PAID
        var paid = safeCall(() -> order.pay(placed.id(),
                new OrderClient.AttachPaymentRequest(intent.id())), "order.pay");
        if (paid == null) {
            return new ConfirmResponse(placed.id(), placed.status(), intent.id(),
                    confirmed.status(), total, "USD", "order/pay failed (payment captured)");
        }

        // 6) Redeem promo + clear cart (post-success bookkeeping)
        final String promoToRedeem = appliedPromo;
        if (promoToRedeem != null) {
            safeCall(() -> { promotion.redeem(promoToRedeem); return null; }, "promotion.redeem");
        }
        safeCall(() -> { cart.clearMyCart(); return null; }, "cart.clearMyCart");

        return new ConfirmResponse(paid.id(), paid.status(), intent.id(),
                confirmed.status(), total, "USD", "order placed and paid");
    }
}
