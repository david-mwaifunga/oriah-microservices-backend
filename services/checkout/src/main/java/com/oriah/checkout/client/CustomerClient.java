package com.oriah.checkout.client;

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The caller's own shipping addresses, read so the shipping zone stops being
 * the client's to choose.
 *
 * <h2>Why checkout needs this</h2>
 *
 * {@code destinationCountry} arrived in the request body and decided the
 * shipping zone, and therefore the shipping charge and the free-shipping
 * threshold. Nothing reconciled it with the address being shipped to, so
 * declaring {@code "NG"} (DOMESTIC: 125.00, free over 3,750.00) for a US
 * address (INTERNATIONAL: 1,125.00, free over 12,500.00) paid 0.00 shipping on
 * a 4,000.00 bag while fulfilment shipped to America.
 *
 * <p>That is the same defect this phase removed for prices -- a client naming a
 * value that decides what is charged -- one leg over. The remedy is the same:
 * take the input away rather than validate it.
 *
 * <h2>Owner-scoped by the token, not by a parameter</h2>
 *
 * {@code GET /api/v1/customers/me/addresses} returns the addresses of whoever
 * the JWT belongs to, and checkout forwards the shopper's own token. So an
 * address id that is not in the returned list is either somebody else's or
 * does not exist, and checkout cannot tell which -- which is exactly the right
 * amount of information for it to have.
 *
 * <p>No fallback, matching the other clients here: an invented address would be
 * an invented shipping charge.
 */
@FeignClient(name = "customer")
public interface CustomerClient {

    @GetMapping("/api/v1/customers/me/addresses")
    List<Address> myAddresses();

    /**
     * Only the two fields the zone decision needs. customer-service sends
     * label, lines, city, state and postcode too; binding them here would make
     * this client break on a change to a field checkout does not read.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Address(UUID id, String country) {}
}
