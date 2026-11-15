package com.oriah.order.client;

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The caller's own addresses, read so an order can only be sent to one of them.
 *
 * <h2>Why order asks, when checkout already has</h2>
 *
 * {@code POST /api/v1/orders} is a ROLE_USER endpoint the gateway routes
 * straight to order, and it stored {@code shippingAddressId} as sent. Checkout
 * does check the address is the shopper's before it gets here, but a shopper
 * who calls order directly skips checkout altogether -- so an order could
 * point at somebody else's address, and from Phase 7 that is where the parcel
 * would go. A check that lives only in the caller is a check the caller can
 * leave out.
 *
 * <h2>Owner-scoped by the token, not by a parameter</h2>
 *
 * {@code GET /api/v1/customers/me/addresses} answers with the addresses of
 * whoever the token belongs to, and this client sends the CALLER's token
 * ({@link com.oriah.order.config.FeignTokenForwarder}, attached here and to
 * {@link PaymentClient} only). So an id missing from the list is either
 * somebody else's or does not exist, and order cannot tell which -- exactly the
 * right amount for it to know. Order's machine token would be the wrong
 * identity: customer would answer for the shop's service account, which has no
 * addresses, and every delivery would be refused.
 *
 * <h2>No fallback</h2>
 *
 * Like every other client here: a fallback would have to invent an answer to
 * "is this address yours?", and the only answer that keeps the shopper moving
 * is the one that lets a parcel go to a stranger. A failed call throws, and the
 * order is not placed.
 */
@FeignClient(name = "customer", configuration = com.oriah.order.config.FeignTokenForwarder.class)
public interface CustomerClient {

    @GetMapping("/api/v1/customers/me/addresses")
    List<Address> myAddresses();

    /**
     * The id, to answer "is this one yours?", and the lines of the address (Phase 8): a parcel is delivered to the address as it was when the order was
     * placed, and a shopper can delete an address later, so order copies the text at placement. The label and the default flag are not read.
     * {@code ignoreUnknown} is on the record, as on checkout's own copy, so that tolerance is part of this contract rather than of mapper settings.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Address(UUID id, String line1, String line2, String city, String state, String postalCode, String country) {

        /** An address known only by its id: enough to be recognised as the caller's. */
        public Address(UUID id) {
            this(id, null, null, null, null, null, null);
        }
    }
}
