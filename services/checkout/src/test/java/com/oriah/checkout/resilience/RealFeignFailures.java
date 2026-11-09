package com.oriah.checkout.resilience;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;

import feign.Client;
import feign.Feign;
import feign.Headers;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Real network failures, raised by Feign itself, for the tests that decide
 * what checkout retries.
 *
 * <h2>Why not a hand-made exception</h2>
 *
 * The retry settings once listed {@code java.io.IOException}, and a test threw
 * an {@code UncheckedIOException} at them and passed. But Feign never lets an
 * IOException out: it wraps every one in {@code feign.RetryableException}, and
 * Resilience4j matches the class of what is thrown. So the setting retried
 * nothing, and the test could not see it. Every failure here is made the way
 * it happens in production -- a port with nothing listening, a server that
 * never answers, a connection dropped after the request went out -- and
 * caught as whatever Feign throws for it.
 *
 * <h2>The client</h2>
 *
 * Built as Spring Cloud OpenFeign builds checkout's clients: Feign's own
 * HttpURLConnection client {@code Client.Default(null, null)} (in checkout the
 * load balancer wraps it, and only picks the address), Feign's own retrying
 * switched off ({@code Retryer.NEVER_RETRY}), and Feign's default error
 * decoder. {@code ConfirmOverRealFeignTest} proves that is what checkout's
 * Spring context really builds.
 */
public final class RealFeignFailures implements AutoCloseable {

    /** A POST with a body, like order.place and payment's two calls. */
    interface Downstream {
        @RequestLine("POST /api/v1/orders")
        @Headers("Content-Type: application/json")
        String post(String body);
    }

    /**
     * Short, for the two failures that are MEANT to give up waiting, so each
     * costs a test a fraction of a second: the connect timeout (with the other
     * failures that never reach a server, refused and unknown host, which end
     * before any wait does) uses both; the read timeout uses only the read wait.
     */
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int READ_TIMEOUT_MS = 300;

    /**
     * Long, for everything else: an answer, or a dropped connection, from the
     * local server. It normally comes in milliseconds, but on a busy machine
     * (a full build, other builds beside it) the server's first answer can take
     * longer than 300 ms -- and with the short wait it then came back as a READ
     * TIMEOUT, so a test about "a 503" failed with a message about something
     * else ({@code RealFeignFailuresTest}). Waiting is free here: a healthy
     * answer ends the wait at once.
     */
    private static final int LOCAL_SERVER_WAIT_MS = 5_000;

    /**
     * RFC 5737's TEST-NET-1: an address reserved for documentation, which
     * nothing on the internet answers and routers usually drop rather than
     * refuse. A connection to it then waits until the connect timeout -- but
     * only on a network that behaves that way; see {@link #connectTimeout()}.
     */
    public static final String BLACK_HOLE = "http://192.0.2.1:81";

    /** A name in the {@code .invalid} domain, which RFC 6761 reserves so that it never resolves. */
    public static final String NO_SUCH_HOST = "http://no-such-host.invalid";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public RealFeignFailures() {
        server.start();
    }

    @Override
    public void close() {
        server.stop();
    }

    /**
     * What Feign throws when nothing listens on the port: the connection is
     * refused. Needs no network: the port is on this machine.
     */
    public static RuntimeException refused() {
        return failureOf("http://localhost:" + closedPort());
    }

    // ------------------------------------------------ failures that need the outside network
    //
    // The two below cannot be made on this machine alone: one needs a network
    // that drops a connection in silence, the other a DNS server that says a
    // name does not exist. Offline, behind a firewall or a VPN, or in a CI box,
    // either can come out as some other failure. So each is made only when a
    // test asks for it (never in a shared @BeforeAll, where one odd network
    // would fail every test in the class), is checked to be what it claims,
    // and -- when this network cannot make it -- SKIPS the test that asked,
    // with a JUnit assumption saying why. Skipped, not passed: a test that
    // proved nothing must not count as green; and not failed: the code is not
    // wrong, the network is unusual. The skip shows in the run's skipped count.
    // Made at most once per run; the outcome, skip included, is remembered.

    private static RuntimeException connectTimeout;
    private static String noConnectTimeout;
    private static RuntimeException unknownHost;
    private static String noUnknownHost;

    /**
     * What Feign throws when the connection is never accepted in time --
     * made by calling {@link #BLACK_HOLE}. Skips the calling test unless the
     * failure really is a CONNECT timeout.
     *
     * <p>Checked by the JDK's message, "Connect timed out": a connect timeout
     * and a read timeout are the same class, so on a network whose proxy
     * accepts the connection and then says nothing, a read timeout would
     * otherwise pass for one. (Production code may not rely on that message;
     * a test checking what it was handed may.)
     */
    public static synchronized RuntimeException connectTimeout() {
        if (connectTimeout == null && noConnectTimeout == null) {
            try {
                RuntimeException failure = failureOf(BLACK_HOLE);
                Throwable root = rootCause(failure);
                if (root instanceof SocketTimeoutException
                        && String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT).contains("connect")) {
                    connectTimeout = failure;
                } else {
                    noConnectTimeout = "this network cannot make a connect timeout: a call to " + BLACK_HOLE
                            + " ended in " + root + " instead of waiting out the " + CONNECT_TIMEOUT_MS
                            + " ms connect timeout (offline, firewalled or on a VPN, the address is refused "
                            + "or routed rather than dropped)";
                }
            } catch (AssertionError answered) {
                noConnectTimeout = "this network cannot make a connect timeout: something answered "
                        + BLACK_HOLE + " (a proxy or captive portal), so the call did not time out";
            }
        }
        Assumptions.assumeTrue(noConnectTimeout == null, noConnectTimeout);
        return connectTimeout;
    }

    /**
     * What Feign throws when the host name does not resolve -- made by calling
     * {@link #NO_SUCH_HOST}, which asks this machine's DNS. Skips the calling
     * test unless the failure really is an {@code UnknownHostException} (a
     * DNS server that answers every name, as some providers' do, would make
     * it something else).
     */
    public static synchronized RuntimeException unknownHost() {
        if (unknownHost == null && noUnknownHost == null) {
            try {
                RuntimeException failure = failureOf(NO_SUCH_HOST);
                Throwable root = rootCause(failure);
                if (root instanceof UnknownHostException) {
                    unknownHost = failure;
                } else {
                    noUnknownHost = "this network cannot make an unknown host: " + NO_SUCH_HOST
                            + " ended in " + root + " -- its DNS seems to answer for names that do not exist";
                }
            } catch (AssertionError answered) {
                noUnknownHost = "this network cannot make an unknown host: " + NO_SUCH_HOST
                        + " resolved and answered -- its DNS answers for names that do not exist";
            }
        }
        Assumptions.assumeTrue(noUnknownHost == null, noUnknownHost);
        return unknownHost;
    }

    /** The innermost cause: the network's own exception under Feign's wrapping. */
    public static Throwable rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * The request reached the server, which never answered in time. The one
     * call here with a short read timeout; it may connect at leisure, so a busy
     * machine cannot turn it into a connect timeout instead.
     */
    public RuntimeException readTimeout() {
        server.resetAll();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(201)
                .withFixedDelay(READ_TIMEOUT_MS * 4).withBody("{}")));
        return failureOf(server.baseUrl(), LOCAL_SERVER_WAIT_MS, READ_TIMEOUT_MS);
    }

    /** The request reached the server, which then dropped the connection. */
    public RuntimeException resetAfterTheRequestWentOut() {
        return faultOf(Fault.CONNECTION_RESET_BY_PEER);
    }

    /** The request reached the server, which closed the connection without a word. */
    public RuntimeException closedWithoutAnAnswer() {
        return faultOf(Fault.EMPTY_RESPONSE);
    }

    /** The server answered {@code status}, with a {@code Retry-After} header when one is given. */
    public RuntimeException answered(int status, String retryAfter) {
        return answeredAfter(status, retryAfter, 0);
    }

    /** As {@link #answered}, the server taking {@code delayMs} to send its answer. */
    RuntimeException answeredAfter(int status, String retryAfter, int delayMs) {
        server.resetAll();
        var response = aResponse().withStatus(status).withFixedDelay(delayMs)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":" + status + "}");
        if (retryAfter != null) {
            response = response.withHeader("Retry-After", retryAfter);
        }
        server.stubFor(any(anyUrl()).willReturn(response));
        RuntimeException failure = failureOf(server.baseUrl(), LOCAL_SERVER_WAIT_MS, LOCAL_SERVER_WAIT_MS);
        if (ResilientFeignCaller.answeredStatus(failure) != status) {
            throw new AssertionError("the local server's " + status + " did not arrive within "
                    + LOCAL_SERVER_WAIT_MS + " ms, so this is not the answer a test asked for: " + failure, failure);
        }
        return failure;
    }

    private RuntimeException faultOf(Fault fault) {
        return faultAfter(fault, 0);
    }

    /** The server, {@code delayMs} after the request arrived, does {@code fault} to the connection. */
    RuntimeException faultAfter(Fault fault, int delayMs) {
        server.resetAll();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withFault(fault).withFixedDelay(delayMs)));
        RuntimeException failure = failureOf(server.baseUrl(), LOCAL_SERVER_WAIT_MS, LOCAL_SERVER_WAIT_MS);
        Throwable root = rootCause(failure);
        if (!(root instanceof SocketException)) {
            throw new AssertionError("the local server's " + fault + " did not reach the client within "
                    + LOCAL_SERVER_WAIT_MS + " ms as a dropped connection, so this is not the failure a test "
                    + "asked for: " + failure, failure);
        }
        return failure;
    }

    /**
     * Calls {@code baseUrl} once, with the short waits, and returns what Feign
     * threw; failing to fail is a broken test. For the failures that never
     * reach a server: refused, unknown host, connect timeout.
     */
    public static RuntimeException failureOf(String baseUrl) {
        return failureOf(baseUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static RuntimeException failureOf(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        Downstream downstream = Feign.builder()
                .client(new Client.Default(null, null))
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(connectTimeoutMs, TimeUnit.MILLISECONDS,
                        readTimeoutMs, TimeUnit.MILLISECONDS, true))
                .target(Downstream.class, baseUrl);
        try {
            downstream.post("{}");
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError(baseUrl + " answered, so it cannot stand for a failure");
    }

    /** A port that was free a moment ago and now has nothing listening on it. */
    public static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
