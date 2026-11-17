package com.oriah.payment.provider.zynlepay;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * How ZynlePay is reached, as far as it is known. Everything here is a setting because the API is known second-hand (two unofficial SDKs) until
 * the merchant account exists, and the SDKs disagree on the status call.
 *
 * @param baseUrl          scheme, host and port
 * @param apiPath          the one JSON door; the operation is named by {@code data.method}
 * @param statusStyle      {@code PATH}: POST {@code statusPath} with {@code {api_id, api_key, reference_no}}; {@code METHOD}: POST {@code apiPath}
 *                         with the usual envelope and {@code data.method = checkPaymentStatus}
 * @param statusPath       used by {@code PATH}
 * @param serviceId        sent in {@code auth.service_id} when set (one SDK sends it, the other does not)
 * @param callbackUrl      sent as {@code data.callback_url} when set; otherwise the address registered in ZynlePay's dashboard is used
 * @param redirectHosts    the hosts (with a port when it is not the scheme's own) a card page may be on; an address anywhere else is withheld
 * @param plainHttpRedirect true only for the simulator: a real card page is https
 */
public record ZynlePayWire(URI baseUrl, String apiPath, StatusStyle statusStyle, String statusPath, ZynlePayCredentials credentials, String serviceId,
                           String callbackUrl, Set<String> redirectHosts, boolean plainHttpRedirect, Duration connectTimeout, Duration readTimeout) {

    public enum StatusStyle { PATH, METHOD }

    /** What identifies Oriah to ZynlePay. Never printed: {@link #toString()} says only that it is there. */
    public record ZynlePayCredentials(String merchantId, String apiId, String apiKey) {

        @Override
        public String toString() {
            return "ZynlePayCredentials[redacted]";
        }
    }
}
