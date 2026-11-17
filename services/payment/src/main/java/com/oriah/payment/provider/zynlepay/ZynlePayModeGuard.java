package com.oriah.payment.provider.zynlepay;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oriah.payment.provider.CheckSchedule;
import com.oriah.payment.provider.FeeTariff;
import com.oriah.payment.provider.Wallets;
import com.oriah.payment.provider.ZynlePayCodes;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.StatusStyle;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.ZynlePayCredentials;

/**
 * Decides, once, when the service starts, which ZynlePay it talks to, and refuses to start when the answer would be unsafe.
 *
 * <p><b>The promise it keeps:</b> a stack that is not production cannot take real money, and production cannot run on the stand-in.
 * <ul>
 *   <li>"Live" is the exact word {@code true} in {@code ORIAH_ZYNLEPAY_LIVE}, read from the process environment ({@link ProcessEnv}). No Spring
 *       property can say it: the config repository is shared by every stack.</li>
 *   <li><b>Not live</b>: {@code mode} chooses payment's own stand-in ({@code SIMULATOR}, which must be on this machine or the payment container) or
 *       ZynlePay's sandbox ({@code SANDBOX}, at its one fixed address, with {@code ORIAH_ZYNLEPAY_SANDBOX_*} credentials). A production credential
 *       present on such a stack refuses the start: half of a live setup where it does not belong is a mistake somebody should see.</li>
 *   <li><b>Live</b>: ZynlePay's production address, fixed in code. The credentials come from the environment only. The wire's shape and the
 *       tariff must be stated (there are no defaults: they are what was proved in the sandbox and agreed with ZynlePay), and
 *       {@code ORIAH_ZYNLEPAY_WIRE_CHECKED} must carry the date the go-live checklist was run. {@code mode} is ignored.</li>
 * </ul>
 *
 * <p>A refusal names the setting, never a value: the message of a failed start is printed in a log.
 */
public final class ZynlePayModeGuard {

    private static final Logger log = LoggerFactory.getLogger(ZynlePayModeGuard.class);

    public enum Mode { SIMULATOR, SANDBOX, LIVE }

    static final String LIVE = "ORIAH_ZYNLEPAY_LIVE";
    static final String MERCHANT_ID = "ORIAH_ZYNLEPAY_MERCHANT_ID";
    static final String API_ID = "ORIAH_ZYNLEPAY_API_ID";
    static final String API_KEY = "ORIAH_ZYNLEPAY_API_KEY";
    static final String WIRE_CHECKED = "ORIAH_ZYNLEPAY_WIRE_CHECKED";
    static final String SANDBOX_MERCHANT_ID = "ORIAH_ZYNLEPAY_SANDBOX_MERCHANT_ID";
    static final String SANDBOX_API_ID = "ORIAH_ZYNLEPAY_SANDBOX_API_ID";
    static final String SANDBOX_API_KEY = "ORIAH_ZYNLEPAY_SANDBOX_API_KEY";

    private static final List<String> LIVE_ONLY = List.of(MERCHANT_ID, API_ID, API_KEY, WIRE_CHECKED);
    private static final List<String> SANDBOX_ONLY = List.of(SANDBOX_MERCHANT_ID, SANDBOX_API_ID, SANDBOX_API_KEY);

    private static final URI PRODUCTION = URI.create("https://payments.zynlepay.com");
    private static final URI SANDBOX = URI.create("https://sandbox.zynlepay.com");
    /** Where the stand-in may be: this machine, or the payment container by its name on the stack's network. */
    private static final Set<String> STAND_IN_HOSTS = Set.of("localhost", "127.0.0.1", "payment");
    public static final String SIMULATOR_PATH = "/internal/zynlepay-sim";

    private static final String PREFIX = "oriah.payment.zynlepay.";
    private static final Map<String, List<String>> USUAL_PREFIXES = Map.of("MTN", List.of("96", "76"), "AIRTEL", List.of("97", "77"),
            "ZAMTEL", List.of("95", "75"));
    private static final Map<String, List<String>> METHODS_OF = Map.of("MOBILE_MONEY", List.of("MTN", "AIRTEL", "ZAMTEL"), "CARD", List.of("CARD"));
    /** The stand-in's placeholder tariff: enough to prove the arithmetic, and nobody's agreement. */
    private static final Map<String, FeeTariff> PLACEHOLDER_FEES = Map.of(
            "MTN", new FeeTariff(new java.math.BigDecimal("2.5"), java.math.BigDecimal.ZERO),
            "AIRTEL", new FeeTariff(new java.math.BigDecimal("2.5"), java.math.BigDecimal.ZERO),
            "ZAMTEL", new FeeTariff(new java.math.BigDecimal("2.5"), java.math.BigDecimal.ZERO),
            "CARD", new FeeTariff(new java.math.BigDecimal("3.5"), java.math.BigDecimal.ZERO));

    private ZynlePayModeGuard() {
    }

    /**
     * Whether payment's own stand-in for ZynlePay exists in this process: only on a stack that is not live and is in SIMULATOR mode. The same two
     * facts {@link #build} decides on, so the stand-in can never sit beside real credentials.
     */
    public static boolean simulatorExists(ProcessEnv env, String mode) {
        return !isLive(env) && (mode == null || mode.isBlank() || "SIMULATOR".equalsIgnoreCase(mode.strip()));
    }

    /**
     * @throws IllegalStateException when the service must not start; the message names the setting at fault
     */
    public static ZynlePaySetup build(ProcessEnv env, ZynlePaySettings settings) {
        Set<String> channels = channels(settings);
        ZynlePayCodes codes = codes(settings.codes());
        Wallets wallets = wallets(settings);
        CheckSchedule schedule = schedule(settings.poll());
        timeout("connect-timeout", settings.connectTimeout());
        timeout("read-timeout", settings.readTimeout());

        ZynlePaySetup setup;
        if (isLive(env)) {
            for (String variable : SANDBOX_ONLY) {
                if (present(env, variable)) {
                    throw refused(variable + " is set on a live stack: sandbox credentials have no place beside the production ones");
                }
            }
            ZynlePayCredentials credentials = new ZynlePayCredentials(required(env, MERCHANT_ID), required(env, API_ID), required(env, API_KEY));
            required(env, WIRE_CHECKED);
            setup = new ZynlePaySetup(Mode.LIVE, provedWire(PRODUCTION, credentials, settings, channels), codes, wallets,
                    tariffs(settings, channels, false), schedule, channels, required("wire.return-url", returnUrl(settings, channels)), settings);
        } else {
            for (String variable : LIVE_ONLY) {
                if (present(env, variable)) {
                    throw refused(variable + " is set, but this stack is not live (" + LIVE + " is not exactly 'true'). A stack that is not live talks "
                            + "only to the stand-in or to ZynlePay's sandbox: remove the variable, or make the stack live");
                }
            }
            String mode = settings.mode() == null ? "SIMULATOR" : settings.mode().strip().toUpperCase(Locale.ROOT);
            switch (mode) {
                case "SANDBOX" -> {
                    ZynlePayCredentials credentials = new ZynlePayCredentials(required(env, SANDBOX_MERCHANT_ID), required(env, SANDBOX_API_ID),
                            required(env, SANDBOX_API_KEY));
                    setup = new ZynlePaySetup(Mode.SANDBOX, provedWire(SANDBOX, credentials, settings, channels), codes, wallets,
                            tariffs(settings, channels, false), schedule, channels, required("wire.return-url", returnUrl(settings, channels)), settings);
                }
                case "SIMULATOR" -> {
                    for (String variable : SANDBOX_ONLY) {
                        if (present(env, variable)) {
                            throw refused(variable + " is set, but " + PREFIX + "mode is SIMULATOR: the stand-in takes no real credentials");
                        }
                    }
                    setup = new ZynlePaySetup(Mode.SIMULATOR, standInWire(settings), codes, wallets, tariffs(settings, channels, true), schedule, channels,
                            settings.card().simulatorReturnUrl(), settings);
                }
                default -> throw refused(PREFIX + "mode must be SIMULATOR or SANDBOX");
            }
        }
        // Which ZynlePay, and never who Oriah is to it.
        log.info("ZynlePay: {} at {}; offering {}", setup.mode(), setup.wire().baseUrl(), channels);
        return setup;
    }

    // ---------------------------------------------------------------- the wire

    /** SANDBOX and live: the shape proved against ZynlePay, every part stated. */
    private static ZynlePayWire provedWire(URI base, ZynlePayCredentials credentials, ZynlePaySettings settings, Set<String> channels) {
        ZynlePaySettings.Wire wire = settings.wire();
        String apiPath = required("wire.api-path", wire.apiPath());
        StatusStyle style;
        try {
            style = StatusStyle.valueOf(required("wire.status-style", wire.statusStyle()).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw refused(PREFIX + "wire.status-style must be PATH or METHOD");
        }
        String statusPath = style == StatusStyle.PATH ? required("wire.status-path", wire.statusPath()) : wire.statusPath();
        Set<String> hosts = new LinkedHashSet<>();
        if (channels.contains("CARD")) {
            if (wire.redirectHosts() == null || wire.redirectHosts().isEmpty()) {
                throw refused(PREFIX + "wire.redirect-hosts is not set: with cards offered, the hosts ZynlePay's card page may be on must be listed");
            }
            wire.redirectHosts().forEach(host -> hosts.add(host.strip().toLowerCase(Locale.ROOT)));
            String returnUrl = required("wire.return-url", wire.returnUrl());
            URI back;
            try {
                back = URI.create(returnUrl.replace("{orderId}", "o").replace("{id}", "i"));
            } catch (IllegalArgumentException e) {
                throw refused(PREFIX + "wire.return-url is not an address");
            }
            if (!"https".equalsIgnoreCase(back.getScheme()) || back.getHost() == null || STAND_IN_HOSTS.contains(back.getHost().toLowerCase(Locale.ROOT))) {
                throw refused(PREFIX + "wire.return-url must be an https page of the shop, not this machine: the customer comes back to it from ZynlePay");
            }
        }
        return new ZynlePayWire(base, apiPath, style, statusPath, credentials, blankToNull(settings.serviceId()), blankToNull(settings.callbackUrl()), hosts,
                false, settings.connectTimeout(), settings.readTimeout());
    }

    private static String returnUrl(ZynlePaySettings settings, Set<String> channels) {
        // Only cards send the customer away and back; without them there is nowhere to come back to.
        return channels.contains("CARD") ? settings.wire().returnUrl() : "-";
    }

    /** The stand-in: this service itself, at fixed paths, with the stand-in's own credentials; its card page is plain http on this machine. */
    private static ZynlePayWire standInWire(ZynlePaySettings settings) {
        URI base;
        try {
            base = URI.create(required("base-url", settings.baseUrl()).strip());
        } catch (IllegalArgumentException e) {
            throw refused(PREFIX + "base-url is not an address");
        }
        if (base.getHost() == null || !STAND_IN_HOSTS.contains(base.getHost().toLowerCase(Locale.ROOT)) || !"http".equalsIgnoreCase(base.getScheme())) {
            throw refused(PREFIX + "base-url must be http on this machine or the payment container in SIMULATOR mode: the stand-in is never anywhere else. "
                    + "ZynlePay's own addresses are not set here; they are chosen by mode SANDBOX or by a live stack");
        }
        String host = base.getHost().toLowerCase(Locale.ROOT) + (base.getPort() < 0 ? "" : ":" + base.getPort());
        ZynlePaySettings.Sim sim = settings.sim();
        return new ZynlePayWire(base, SIMULATOR_PATH + "/jsonapi", StatusStyle.PATH, SIMULATOR_PATH + "/jsonapi/paymentstatus",
                new ZynlePayCredentials(sim.merchantId(), sim.apiId(), sim.apiKey()), null, null, Set.of(host), true, settings.connectTimeout(),
                settings.readTimeout());
    }

    // ---------------------------------------------------------------- the rest

    private static Set<String> channels(ZynlePaySettings settings) {
        Set<String> channels = new LinkedHashSet<>();
        for (String channel : settings.channels() == null ? List.<String>of() : settings.channels()) {
            String name = channel.strip().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (!METHODS_OF.containsKey(name)) {
                throw refused(PREFIX + "channels: " + name + " is not a channel; there are MOBILE_MONEY and CARD");
            }
            channels.add(name);
        }
        if (channels.isEmpty()) {
            throw refused(PREFIX + "channels is empty: offer MOBILE_MONEY, CARD or both");
        }
        return channels;
    }

    private static Map<String, FeeTariff> tariffs(ZynlePaySettings settings, Set<String> channels, boolean placeholdersAllowed) {
        Map<String, FeeTariff> tariffs = new LinkedHashMap<>();
        for (String channel : channels) {
            for (String method : METHODS_OF.get(channel)) {
                ZynlePaySettings.Fee fee = settings.fees() == null ? null : settings.fees().get(method);
                if (fee == null || fee.percent() == null) {
                    if (fee == null && placeholdersAllowed) {
                        tariffs.put(method, PLACEHOLDER_FEES.get(method));
                        continue;
                    }
                    throw refused(PREFIX + "fees." + method + " is not set: ZynlePay's answers carry no fee, so the tariff agreed with ZynlePay must be "
                            + "stated (percent, and fixed if there is one)");
                }
                try {
                    tariffs.put(method, new FeeTariff(fee.percent(), fee.fixed() == null ? java.math.BigDecimal.ZERO : fee.fixed()));
                } catch (IllegalArgumentException | ArithmeticException e) {
                    throw refused(PREFIX + "fees." + method + ": " + e.getMessage());
                }
            }
        }
        return tariffs;
    }

    private static ZynlePayCodes codes(ZynlePaySettings.Codes c) {
        try {
            return new ZynlePayCodes(c.initiated(), c.paid(), c.pending(), c.failed(), c.refused(), c.credentials(), c.ambiguous(), c.notFound());
        } catch (IllegalArgumentException e) {
            throw refused(PREFIX + "codes: " + e.getMessage());
        }
    }

    private static Wallets wallets(ZynlePaySettings settings) {
        try {
            return new Wallets(settings.walletPrefixes() == null || settings.walletPrefixes().isEmpty() ? USUAL_PREFIXES : settings.walletPrefixes());
        } catch (IllegalArgumentException e) {
            throw refused(PREFIX + "wallet-prefixes: " + e.getMessage());
        }
    }

    private static CheckSchedule schedule(ZynlePaySettings.Poll poll) {
        try {
            return new CheckSchedule(poll.firstCheckAfter(), poll.fastEvery(), poll.fastFor().mobileMoney(), poll.fastFor().card(), poll.slowEvery(),
                    poll.indeterminateWait(), poll.giveUpAfter(), poll.unresolvedEvery(), poll.stopAfter(), poll.finalRecheck());
        } catch (IllegalArgumentException e) {
            throw refused(e.getMessage());
        }
    }

    private static void timeout(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw refused(PREFIX + name + " must be a length of time above nothing");
        }
    }

    private static boolean isLive(ProcessEnv env) {
        return "true".equals(env.get(LIVE));
    }

    private static boolean present(ProcessEnv env, String variable) {
        String value = env.get(variable);
        return value != null && !value.isBlank();
    }

    private static String required(ProcessEnv env, String variable) {
        if (!present(env, variable)) {
            throw refused(variable + " is not set in the environment of this process");
        }
        return env.get(variable).strip();
    }

    private static String required(String setting, String value) {
        if (value == null || value.isBlank()) {
            throw refused(PREFIX + setting + " is not set: it has no default, because it is what was proved against ZynlePay itself");
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static IllegalStateException refused(String why) {
        return new IllegalStateException("payment will not start: " + why);
    }
}
