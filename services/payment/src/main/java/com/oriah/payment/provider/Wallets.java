package com.oriah.payment.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which wallet a Zambian mobile number belongs to, by the two digits after {@code +260}. It decides the method the money is recorded under
 * ({@code MTN}, {@code AIRTEL}, {@code ZAMTEL}), the tariff and the words the customer reads; the shopper types only the number.
 *
 * <p>Numbers are read in the one form they are kept in, {@code +260} and nine digits. The prefixes are a setting, because the networks add ranges.
 */
public final class Wallets {

    private static final Set<String> KNOWN = Set.of("MTN", "AIRTEL", "ZAMTEL");
    private static final String COUNTRY = "+260";

    private final Map<String, String> walletByPrefix = new HashMap<>();

    public Wallets(Map<String, List<String>> prefixes) {
        prefixes.forEach((wallet, list) -> {
            if (!KNOWN.contains(wallet)) {
                throw new IllegalArgumentException("wallet " + wallet + " is not one of " + KNOWN);
            }
            for (String prefix : list) {
                if (prefix == null || !prefix.matches("[0-9]{2}")) {
                    throw new IllegalArgumentException("wallet prefix '" + prefix + "' of " + wallet + " is not two digits");
                }
                String other = walletByPrefix.put(prefix, wallet);
                if (other != null) {
                    throw new IllegalArgumentException("wallet prefix " + prefix + " is listed under " + other + " and " + wallet);
                }
            }
        });
    }

    /** @return the wallet of a number in the form {@code +260} and nine digits, or empty when it is not a number of a listed wallet */
    public Optional<String> of(String msisdn) {
        if (msisdn == null || !msisdn.matches("\\+260[0-9]{9}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(walletByPrefix.get(msisdn.substring(COUNTRY.length(), COUNTRY.length() + 2)));
    }

    /** The number as ZynlePay is sent it: the digits, without the plus. */
    public static String digits(String msisdn) {
        return msisdn.substring(1);
    }

    /** The number as it is shown: its first five and last three characters ({@code +2609*****567}). */
    public static String masked(String msisdn) {
        if (msisdn == null) {
            return null;
        }
        if (msisdn.length() < 9) {
            return "*".repeat(msisdn.length());
        }
        return msisdn.substring(0, 5) + "*".repeat(msisdn.length() - 8) + msisdn.substring(msisdn.length() - 3);
    }
}
