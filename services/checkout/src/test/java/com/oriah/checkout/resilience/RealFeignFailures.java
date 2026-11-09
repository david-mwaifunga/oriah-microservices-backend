package com.oriah.checkout.resilience;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

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

    /** Short, so a failure costs a test a fraction of a second. */
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int READ_TIMEOUT_MS = 300;

    /**
     * RFC 5737's TEST-NET-1: an address reserved for documentation, which
     * nothing on the internet answers and routers drop rather than refuse.
     * A connection to it waits until the connect timeout.
     */
    public static final String BLACK_HOLE = "http://192.0.2.1:81";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public RealFeignFailures() {
        server.start();
    }

    @Override
    public void close() {
        server.stop();
    }

    /** What Feign throws when nothing listens on the port: the connection is refused. */
    public static RuntimeException refused() {
        return failureOf("http://localhost:" + closedPort());
    }

    /** What Feign throws when the host name does not resolve. */
    public static RuntimeException unknownHost() {
        return failureOf("http://no-such-host.invalid");
    }

    /** What Feign throws when the connection is never accepted in time. */
    public static RuntimeException connectTimeout() {
        return failureOf(BLACK_HOLE);
    }

    /** The request reached the server, which never answered in time. */
    public RuntimeException readTimeout() {
        server.resetAll();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(201)
                .withFixedDelay(READ_TIMEOUT_MS * 4).withBody("{}")));
        return failureOf(server.baseUrl());
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
        server.resetAll();
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":" + status + "}");
        if (retryAfter != null) {
            response = response.withHeader("Retry-After", retryAfter);
        }
        server.stubFor(any(anyUrl()).willReturn(response));
        return failureOf(server.baseUrl());
    }

    private RuntimeException faultOf(Fault fault) {
        server.resetAll();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withFault(fault)));
        return failureOf(server.baseUrl());
    }

    /** Calls {@code baseUrl} once and returns what Feign threw; failing to fail is a broken test. */
    public static RuntimeException failureOf(String baseUrl) {
        Downstream downstream = Feign.builder()
                .client(new Client.Default(null, null))
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                        READ_TIMEOUT_MS, TimeUnit.MILLISECONDS, true))
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
