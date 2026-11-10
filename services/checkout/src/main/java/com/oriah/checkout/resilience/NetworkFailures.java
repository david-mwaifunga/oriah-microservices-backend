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
 * ("Retried" is on every rule. payment-create alone also retries every row
 * marked otherwise -- "no route" and the four below it, any network failure
 * at all -- its call being idempotent.)
 *
 * Every row but "no route" is produced for real -- by {@code RealFeignFailures},
 * and checked by {@code ResilienceConfigResolutionTest} and
 * {@code ConfirmOverRealFeignTest}. Three limits on that:
 *
 * <ul>
 *   <li>"No route" is NOT produced by any test: no test here can make one for
 *       real. It is listed from how the JDK reports it. It arrives as a bare
 *       {@code SocketException} -- the very type a reset after sending has -- so
 *       it cannot be told apart and is not retried. (A Linux JDK raises
 *       {@code NoRouteToHostException} for some of these.)</li>
 *   <li>The connect timeout is produced by calling 192.0.2.1, an address
 *       reserved for documentation that networks normally drop in silence, and
 *       the unknown host by asking DNS for a name under {@code .invalid}. Both
 *       need the outside network to behave as usual. Offline, behind a
 *       firewall or a VPN, the call may instead be refused, or answered; so
 *       each is made only by the test that needs it, checked to be what it
 *       claims, and that test is SKIPPED, saying why, where the network cannot
 *       make it -- neither passed without proving anything, nor failed for a
 *       reason that is not checkout's.</li>
 *   <li>The two timeouts are the same class, told apart only by the JDK's
 *       message text, which is no contract. Mistaking a read timeout for a
 *       connect timeout would retry an order that was saved, so neither is
 *       retried.</li>
 * </ul>
 *
 * <h2>Answers: 502, 503 and 504</h2>
 *
 * An answered 502, 503 or 504 ({@link #answeredUnavailable}) is retried on the
 * default rule and payment-create's -- unless it carries {@code Retry-After}
 * ({@link #askedToComeBackLater}), which no rule repeats. Payment's confirm
 * repeats no answer at all ({@link RetryForChargingCall}).
 *
 * <h2>The load balancer's "no instance"</h2>
 *
 * When service discovery holds no address for the service, Spring Cloud's load
 * balancer sends nothing and answers a 503 it makes up itself. It counts as
 * never arrived, so it is retried on every rule, confirm's included (safe:
 * nothing was sent), and payment's confirm in this state is the 503 "nothing
 * has been charged" rather than the 502 "it may have gone through". How it is
 * told apart from a real 503: {@link #madeUpByTheLoadBalancer}. It carries no
 * headers, so never a {@code Retry-After}.
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
     * not known yet" -- the money may have been taken -- which is why confirm's
     * rule ({@link RetryForChargingCall}) repeats no answer at all.
     *
     * <p>Read from the status, not the exception class: when the answer carries
     * a {@code Retry-After} header Feign can read -- as order's 503 does when
     * customer, Keycloak or shipping let it down, and payment's on create --
     * Feign's default error decoder throws {@code feign.RetryableException}
     * carrying the status, not {@code FeignException$ServiceUnavailable}.
     */
    public static boolean answeredUnavailable(Throwable failure) {
        int status = ResilientFeignCaller.answeredStatus(failure);
        return status == 502 || status == 503 || status == 504;
    }

    /**
     * The service answered 502, 503 or 504 AND said when to come back: its
     * answer carries a {@code Retry-After} header.
     *
     * <h3>Why that answer is not repeated</h3>
     *
     * Order and payment send a 503 with {@code Retry-After: 5} only after
     * waiting on their own dependencies -- order when customer, Keycloak (its
     * token) or shipping let it down; payment's create when it could not ask
     * order whose order it is. So the answer itself can take many seconds to
     * arrive. Asking again half a second later waits all of that again, up to
     * twice more: order.place's slow 503 grew from about 15 s to about 46 s
     * that way, past the 30 s the storefront waits for checkout. And the
     * service has just said, in so many words, "I am struggling; not before 5
     * seconds". Checkout honours that by not repeating the call: the shopper
     * gets checkout's own 503, with checkout's own {@code Retry-After} (always
     * 5 seconds -- the downstream's value is not copied).
     *
     * <p>Two kinds are not this, and are still repeated: the load balancer's
     * own "no instance" 503 (it carries no headers at all, and nothing was
     * sent -- {@link #madeUpByTheLoadBalancer}), and a 502, 503 or 504 that does
     * not say when to come back.
     *
     * <p>Order's 503s all say when to come back: the one for customer,
     * Keycloak or shipping and the one for catalog (order's
     * {@code GlobalExceptionHandler}, both {@code Retry-After: 5}). That
     * mattered for catalog: that 503 used to carry none, so checkout repeated
     * order.place three times, each attempt asking customer and catalog again
     * (up to 20.5 s in all) while catalog struggled. A 503 without
     * {@code Retry-After} still comes from somewhere -- a proxy, a service
     * that does not send it -- and is repeated. Payment's confirm 503 ("the
     * outcome is not known yet") carries none, but confirm's rule repeats no
     * answer at all.
     *
     * <p>Read from the header itself, not from Feign's {@code retryAfter()}:
     * when the value cannot be read as seconds or a date, Feign's error decoder
     * drops it and throws a plain {@code FeignException}, but the service still
     * asked for later. When it can, Feign throws {@code RetryableException}
     * with the answer -- headers and all -- as its cause, so the walk down the
     * causes finds the header either way. Names are compared ignoring case, as
     * HTTP says.
     */
    public static boolean askedToComeBackLater(Throwable failure) {
        if (!answeredUnavailable(failure)) {
            return false;
        }
        for (Throwable c = failure; c != null; c = c.getCause()) {
            if (c instanceof FeignException fe && fe.responseHeaders().keySet().stream()
                    .anyMatch(RETRY_AFTER::equalsIgnoreCase)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static final String RETRY_AFTER = "Retry-After";

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
