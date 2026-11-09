package com.oriah.checkout.resilience;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import feign.FeignException;

/**
 * What a failed Feign call says about whether the request reached the service.
 *
 * <h2>Why it matters</h2>
 *
 * Retrying is only safe when a second attempt cannot do something twice. For
 * order's {@code POST /orders} -- not idempotent: the same request sent twice
 * saves two orders -- that means retrying only when the first attempt
 * certainly never arrived. "The service did not answer" is not enough: a reply
 * can be lost after the order was saved.
 *
 * <h2>How Feign reports a network failure</h2>
 *
 * Checkout's clients run on Feign's own HttpURLConnection client, behind Spring
 * Cloud's load balancer. Feign never lets the network's IOException out: it
 * wraps every one in {@code feign.RetryableException}, status -1, with the
 * IOException as the cause. Measured through that client on this project's
 * machine (Windows, JDK 25):
 *
 * <pre>
 *   nothing listening on the port   ConnectException          never arrived  -> retried
 *   host name does not resolve      UnknownHostException      never arrived  -> retried
 *   no route / network unreachable  SocketException           (see below)    -> not retried
 *   connect timeout                 SocketTimeoutException    "Connect timed out"
 *   read timeout                    SocketTimeoutException    "Read timed out"
 *   reset after the request went    SocketException           may have arrived
 *   closed with no answer           SocketException           may have arrived
 * </pre>
 *
 * Every row but "no route" is produced for real -- by {@code RealFeignFailures},
 * and checked by {@code ResilienceConfigResolutionTest} and
 * {@code ConfirmOverRealFeignTest}. Two limits on that:
 *
 * <ul>
 *   <li>"No route" is NOT produced by any test: no test here can make one for
 *       real. It is listed from how the JDK reports it. It arrives as a bare
 *       {@code SocketException} -- the very type a reset after sending has -- so
 *       it cannot be told apart and is not retried. (A Linux JDK raises
 *       {@code NoRouteToHostException} for some of these.)</li>
 *   <li>The connect timeout is produced by calling 192.0.2.1, an address
 *       reserved for documentation that networks normally drop in silence. On
 *       a machine with no network at all it may instead fail at once with a
 *       {@code SocketException}; the test that relies on it then fails and
 *       says so, rather than passing without proving anything.</li>
 *   <li>The two timeouts are the same class, told apart only by the JDK's
 *       message text, which is no contract. Mistaking a read timeout for a
 *       connect timeout would retry an order that was saved, so neither is
 *       retried.</li>
 * </ul>
 *
 * <h2>The load balancer's "no instance"</h2>
 *
 * When service discovery holds no address for the service, Spring Cloud's load
 * balancer sends nothing and answers a 503 it makes up itself. It is retried as
 * any 503 is (safe: nothing was sent), and it counts as never arrived, so that
 * payment's confirm in this state is the 503 "nothing has been charged" rather
 * than the 502 "it may have gone through". How it is told apart from a real
 * 503: {@link #madeUpByTheLoadBalancer}.
 */
public final class NetworkFailures {

    private NetworkFailures() {
    }

    /**
     * The call failed without the request ever reaching the service: the
     * connection could not even be opened, or the load balancer had no
     * address to send it to. Safe to repeat for ANY call, a non-idempotent
     * POST included.
     */
    public static boolean neverArrived(Throwable failure) {
        if (madeUpByTheLoadBalancer(failure)) {
            return true;
        }
        if (ResilientFeignCaller.answeredStatus(failure) >= 0) {
            return false;
        }
        for (Throwable c = failure; c != null; c = c.getCause()) {
            // ConnectException is thrown only while opening the connection
            // (refused, or the system's own connect timeout), never once the
            // request is on its way.
            if (c instanceof ConnectException || c instanceof UnknownHostException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * The start of what Spring Cloud's {@code FeignBlockingLoadBalancerClient}
     * writes in the 503 it makes up when it has no address for the service
     * (spring-cloud-openfeign 5.0.1, followed by the service's name).
     */
    static final String NO_INSTANCE = "Load balancer does not contain an instance for the service ";

    /**
     * The 503 the load balancer made up itself: it had no address, so nothing
     * was sent.
     *
     * <p>Recognised by two things together: its words, and having no HTTP
     * headers at all. The words alone are not enough -- they are only text,
     * and taking a real answer from payment for "nothing was sent" would tell a
     * shopper who may have been charged that they were not. Every answer that
     * crossed the network carries headers; the load balancer's own carries
     * none. If a Spring Cloud upgrade changes the words, this simply stops
     * matching and the call is treated as "may have arrived" -- the safe side
     * -- and {@code ConfirmOverRealFeignTest} goes red.
     */
    private static boolean madeUpByTheLoadBalancer(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof FeignException fe) {
                return fe.status() == 503 && fe.responseHeaders().isEmpty() && fe.responseBody()
                        .map(b -> StandardCharsets.UTF_8.decode(b.duplicate()).toString())
                        .filter(body -> body.startsWith(NO_INSTANCE))
                        .isPresent();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * The service answered 502, 503 or 504: it says it is unavailable. The
     * same three statuses {@code CheckoutService} reports as a 503 to the
     * shopper.
     *
     * <p>For every call but one that also means it did not do the work. The
     * one is payment's confirm, whose 503 means "the outcome of the charge is
     * not known yet" -- the money may have been taken; see
     * {@link RetryForAnyCall} for why repeating it is still safe.
     *
     * <p>Read from the status, not the exception class: when the answer carries
     * a {@code Retry-After} header -- order's and payment's 503s do -- Feign's
     * default error decoder throws {@code feign.RetryableException} carrying
     * the status, not {@code FeignException$ServiceUnavailable}.
     */
    public static boolean answeredUnavailable(Throwable failure) {
        int status = ResilientFeignCaller.answeredStatus(failure);
        return status == 502 || status == 503 || status == 504;
    }

    /**
     * No complete answer arrived, for whatever network reason: the request may
     * or may not have been acted on. Only a call the service makes safe to
     * repeat -- an idempotent one -- may be retried on this.
     *
     * <p>Feign's status -1 failures (a timeout, a reset, a connection closed
     * with no answer), and a 2xx whose body broke off on the way, which Feign
     * reports with the status it had read and the IOException as the cause.
     */
    public static boolean noCompleteAnswer(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof IOException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
