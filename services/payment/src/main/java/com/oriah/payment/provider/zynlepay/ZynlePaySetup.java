package com.oriah.payment.provider.zynlepay;

import java.util.Map;
import java.util.Set;

import com.oriah.payment.provider.CheckSchedule;
import com.oriah.payment.provider.FeeTariff;
import com.oriah.payment.provider.Wallets;
import com.oriah.payment.provider.ZynlePayCodes;

/**
 * How this process collects through ZynlePay, as {@link ZynlePayModeGuard} decided it when the service started: which ZynlePay (the stand-in, the
 * sandbox or production), how it is reached, what its codes mean, the tariff, and when it is asked about a payment.
 *
 * @param returnUrl where a customer comes back to after the card page, with {@code {orderId}} and {@code {id}} to fill in
 */
public record ZynlePaySetup(ZynlePayModeGuard.Mode mode, ZynlePayWire wire, ZynlePayCodes codes, Wallets wallets, Map<String, FeeTariff> tariffs,
                            CheckSchedule schedule, Set<String> channels, String returnUrl, ZynlePaySettings settings) {

    /** The tariff of a method the shop offers. */
    public FeeTariff tariff(String method) {
        FeeTariff tariff = tariffs.get(method);
        if (tariff == null) {
            throw new IllegalStateException("no tariff for " + method + ": its channel is not offered");
        }
        return tariff;
    }

    public boolean offers(String channel) {
        return channels.contains(channel);
    }
}
