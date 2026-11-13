package com.oriah.checkout.dto;

import java.util.UUID;

/** How the shopper chose to have the bag delivered (Phase 7: priced per town): the option, the town, and who pays the courier. */
public interface DeliveryChoice {

    String deliveryOption();

    UUID deliveryTownId();

    String payer();
}
