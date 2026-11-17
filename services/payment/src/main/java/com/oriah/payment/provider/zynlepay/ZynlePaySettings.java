package com.oriah.payment.provider.zynlepay;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code oriah.payment.zynlepay.*}: everything about collecting through ZynlePay that may be set in a file. What may NOT be set in a file (whether
 * this stack is live, and the credentials) is read from the process's environment by {@link ZynlePayModeGuard}.
 *
 * @param mode           {@code SIMULATOR} (payment's own stand-in; the default) or {@code SANDBOX} (ZynlePay's test system). Ignored when live.
 * @param channels       what the shop offers: {@code MOBILE_MONEY}, {@code CARD}
 * @param baseUrl        where the stand-in is reached (this service itself). Only used in SIMULATOR mode: the sandbox and production addresses are fixed.
 * @param wire           how ZynlePay's API is shaped, as proved in its sandbox. No defaults: SANDBOX and live refuse to start without it.
 * @param codes          what ZynlePay's response codes mean
 * @param fees           the tariff per method (MTN, AIRTEL, ZAMTEL, CARD). Required for SANDBOX and live; the stand-in has placeholders.
 * @param walletPrefixes the two digits after +260 of each wallet's numbers
 */
@ConfigurationProperties(prefix = "oriah.payment.zynlepay")
public record ZynlePaySettings(
        @DefaultValue("SIMULATOR") String mode,
        @DefaultValue({"MOBILE_MONEY", "CARD"}) List<String> channels,
        @DefaultValue("http://localhost:8085") String baseUrl,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout,
        String serviceId,
        String callbackUrl,
        @DefaultValue Wire wire,
        @DefaultValue Codes codes,
        Map<String, Fee> fees,
        Map<String, List<String>> walletPrefixes,
        @DefaultValue Card card,
        @DefaultValue Limits limits,
        @DefaultValue Poll poll,
        @DefaultValue Callback callback,
        @DefaultValue Sim sim) {

    /**
     * @param statusStyle   {@code PATH} or {@code METHOD}: the two ways the status call is described (see {@link ZynlePayWire})
     * @param redirectHosts the hosts a card page may be on
     * @param returnUrl     where the customer comes back to after ZynlePay's card page: an https page of the shop, with {orderId} and {id}
     */
    public record Wire(String apiPath, String statusStyle, String statusPath, List<String> redirectHosts, String returnUrl) {
    }

    /** Each entry is a code or an inclusive range ({@code 9900-9915}). {@code refused} is empty until ZynlePay says which refusals are final. */
    public record Codes(
            @DefaultValue("120") List<String> initiated,
            @DefaultValue("100") List<String> paid,
            @DefaultValue("990") List<String> pending,
            @DefaultValue("995") List<String> failed,
            List<String> refused,
            @DefaultValue("9900-9915") List<String> credentials,
            List<String> ambiguous,
            List<String> notFound) {
    }

    /** A percentage of the amount plus a fixed part, in Kwacha. */
    public record Fee(BigDecimal percent, BigDecimal fixed) {
    }

    /** What ZynlePay's card page is told about the payer besides the name and email on the shopper's own account. */
    public record Card(
            @DefaultValue("Lusaka, Zambia") String payerAddress,
            @DefaultValue("Lusaka") String payerCity,
            @DefaultValue("http://localhost:4000/orders/{orderId}/payment?attempt={id}") String simulatorReturnUrl) {
    }

    /** How many payments one shopper, and one number, may be asked for in an hour: a prompt on somebody's phone is not free to send. */
    public record Limits(
            @DefaultValue("6") int startsPerShopperPerHour,
            @DefaultValue("4") int startsPerNumberPerHour) {
    }

    /**
     * The checker that asks ZynlePay what became of a payment.
     *
     * @param delay           milliseconds between the end of one pass and the start of the next
     * @param batch           how many attempts one pass asks about
     * @param lease           how long one asker keeps an attempt to itself
     * @param lateCheckWindow how long after it failed an attempt can still be asked about (a callback or a staff check)
     * @param callbackMinGap  the least time between two questions a callback can bring forward
     * @param startingGrace   how long an attempt may sit unsent before it is failed as never sent
     */
    public record Poll(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5000") long delay,
            @DefaultValue("5") int batch,
            @DefaultValue("PT2M") Duration lease,
            @DefaultValue("PT15S") Duration firstCheckAfter,
            @DefaultValue("PT15S") Duration fastEvery,
            @DefaultValue FastFor fastFor,
            @DefaultValue("PT60S") Duration slowEvery,
            @DefaultValue("PT60S") Duration indeterminateWait,
            @DefaultValue("PT30M") Duration giveUpAfter,
            @DefaultValue("PT1H") Duration unresolvedEvery,
            @DefaultValue("P14D") Duration stopAfter,
            @DefaultValue("PT15M") Duration finalRecheck,
            @DefaultValue("P14D") Duration lateCheckWindow,
            @DefaultValue("PT10S") Duration callbackMinGap,
            @DefaultValue("PT2M") Duration startingGrace) {
    }

    public record FastFor(
            @DefaultValue("PT5M") Duration mobileMoney,
            @DefaultValue("PT20M") Duration card) {
    }

    public record Callback(@DefaultValue("8192") int maxBytes) {
    }

    /**
     * The stand-in: the credentials it expects (not secrets: they open nothing but the stand-in), where it sends its callbacks, and how long it
     * holds back an answer that is meant to be lost.
     */
    public record Sim(
            @DefaultValue("M-SIM") String merchantId,
            @DefaultValue("API-ID-SIM") String apiId,
            @DefaultValue("API-KEY-SIM") String apiKey,
            String callbackUrl,
            @DefaultValue("PT12S") Duration loseAnswerFor) {
    }
}
