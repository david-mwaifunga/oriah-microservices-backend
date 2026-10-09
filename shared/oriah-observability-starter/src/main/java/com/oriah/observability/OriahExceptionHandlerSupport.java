package com.oriah.observability;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The shared RFC 7807 error contract, as a base class each service's own
 * {@code exception/GlobalExceptionHandler} extends.
 *
 * <h2>Why this is abstract and carries no annotation</h2>
 *
 * <p>This is the course's {@code GlobalExceptionHandler} convention adapted
 * to a system with 13 Spring contexts instead of one. The course puts
 * {@code @ControllerAdvice} on a class each service owns outright. Here the
 * shared rendering has to live in one place, so the obvious move is to
 * annotate the shared base class and let the services extend it — and that is
 * precisely what must not happen. {@code @ControllerAdvice} is
 * {@code @Inherited}-by-component-scan in effect: the base bean registers,
 * each subclass bean registers, and both advise the same context while both
 * claim the inherited {@code @ExceptionHandler(Exception.class)}. Spring does
 * not reject that. It orders the two advices by {@code Ordered} — and with
 * neither ordered, both sit at {@code LOWEST_PRECEDENCE} and which one claims
 * a given exception is decided by bean-name sort. Error responses would become
 * a function of class naming.
 *
 * <p>So the annotation belongs on the leaves. This class is abstract and
 * unannotated, and is never itself a bean. {@link ProblemDetailExceptionHandler}
 * is the concrete default for modules with no domain exceptions of their own
 * (the gateway, configserver, eurekaserver), registered by
 * {@link ObservabilityAutoConfiguration} only when the module supplies no
 * advice of its own.
 *
 * <h2>Why it does not extend {@code ResponseEntityExceptionHandler}</h2>
 *
 * <p>That is the course's base class, and copying it is not possible here.
 * It lives in <strong>spring-webmvc</strong>, and this starter is on the
 * classpath of the <em>reactive</em> gatewayserver. Spring Boot's
 * {@code WebApplicationType.deduceFromClasspath()} treats webflux + webmvc as
 * {@code SERVLET}, so the gateway would boot a servlet container, never wire
 * the Spring Cloud Gateway filter chain, and route nothing — while still
 * reporting a successful start. Every exception type handled below ships in
 * <strong>spring-web</strong> instead, which both stacks share, and
 * {@code GatewayServerWebApplicationTypeTest} enforces the distinction so it
 * cannot be undone by a careless dependency addition.
 *
 * <h2>Status mapping</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException}                 -&gt; 400 BAD_REQUEST
 *   <li>{@link MethodArgumentNotValidException}          -&gt; 400 (with field errors)
 *   <li>{@link EntityNotFoundException}                  -&gt; 404 NOT_FOUND
 *   <li>{@link IllegalStateException}                    -&gt; 409 CONFLICT
 *   <li>{@link AccessDeniedException}                    -&gt; 403 FORBIDDEN
 *   <li>{@link HttpRequestMethodNotSupportedException}   -&gt; 405 (with {@code Allow})
 *   <li>{@link HttpMediaTypeNotSupportedException}       -&gt; 415 (with {@code Accept})
 *   <li>{@link HttpMediaTypeNotAcceptableException}      -&gt; 406
 *   <li>{@link HttpMessageNotReadableException}          -&gt; 400 (malformed body)
 *   <li>{@link MissingServletRequestParameterException}  -&gt; 400
 *   <li>{@link MissingRequestHeaderException}            -&gt; 400
 *   <li>{@link MethodArgumentTypeMismatchException}      -&gt; 400
 *   <li>{@link ServletRequestBindingException}           -&gt; 400 (remaining binding failures)
 *   <li>{@link ResponseStatusException}                  -&gt; whatever status it carries
 *   <li>everything else                                  -&gt; 500 INTERNAL_SERVER_ERROR
 * </ul>
 *
 * <p>Every body carries a {@code traceId} from the Micrometer-managed SLF4J
 * MDC, so a client holding an error response is one paste away from the
 * failing call path in Tempo.
 *
 * <p>5xx logs at ERROR, 4xx at DEBUG — the caller already knows they sent
 * something invalid, and a 4xx is not an ops event.
 *
 * <h2>Extending it</h2>
 *
 * <p>A service adds handlers for its own domain exceptions and inherits
 * everything above:
 *
 * <pre>{@code
 * @RestControllerAdvice
 * public class GlobalExceptionHandler extends OriahExceptionHandlerSupport {
 *
 *     @ExceptionHandler(ResourceNotFoundException.class)
 *     public ProblemDetail notFound(ResourceNotFoundException ex) {
 *         return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not Found", ex.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <p>A subclass handler for a specific type wins over an inherited handler for
 * a supertype, because Spring resolves the most specific match within an
 * advice regardless of which class in the hierarchy declared it.
 */
@Slf4j
public abstract class OriahExceptionHandlerSupport {

    protected static final URI TYPE_BAD_REQUEST = URI.create("https://oriah.dev/problems/bad-request");
    protected static final URI TYPE_VALIDATION  = URI.create("https://oriah.dev/problems/validation");
    protected static final URI TYPE_NOT_FOUND   = URI.create("https://oriah.dev/problems/not-found");
    protected static final URI TYPE_CONFLICT    = URI.create("https://oriah.dev/problems/conflict");
    protected static final URI TYPE_FORBIDDEN   = URI.create("https://oriah.dev/problems/forbidden");
    protected static final URI TYPE_INTERNAL    = URI.create("https://oriah.dev/problems/internal-server-error");
    protected static final URI TYPE_METHOD      = URI.create("https://oriah.dev/problems/method-not-allowed");
    protected static final URI TYPE_MEDIA_TYPE  = URI.create("https://oriah.dev/problems/unsupported-media-type");
    protected static final URI TYPE_NOT_ACCEPT  = URI.create("https://oriah.dev/problems/not-acceptable");

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badArgument(IllegalArgumentException ex) {
        log.debug("400 IllegalArgumentException: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(MethodArgumentNotValidException ex) {
        log.debug("400 validation failure: {}", ex.getMessage());
        List<FieldErrorView> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorView(fe.getField(), fe.getDefaultMessage()));
        }
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Validation Failed",
                "Request validation failed");
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException ex) {
        log.debug("404 EntityNotFoundException: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * Spring Security's {@link AccessDeniedException} — thrown by
     * {@code @PreAuthorize} when a role check fails. Without this handler the
     * generic {@link Exception} catch below would map it to 500; an explicit
     * 403 lets the client distinguish "you are not allowed" from "something on
     * the server broke."
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException ex) {
        log.debug("403 AccessDeniedException: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, TYPE_FORBIDDEN, "Forbidden", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException ex) {
        log.debug("409 IllegalStateException: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Spring MVC's own exceptions.
    //
    // These must be declared explicitly. The @ExceptionHandler(Exception.class)
    // catch-all below lives in the same advice, and
    // ExceptionHandlerExceptionResolver is consulted *before*
    // DefaultHandlerExceptionResolver -- so without these methods the
    // catch-all claims every framework exception and renders it as 500,
    // flattening 405/415/406/400. FrameworkErrorContractTest was written
    // against exactly that defect and pins the corrected mapping.
    // ------------------------------------------------------------------

    /**
     * 405. Returns {@link ResponseEntity} rather than a bare
     * {@link ProblemDetail} so the response can carry {@code Allow}, which
     * RFC 9110 says a 405 SHOULD include — it is the only way a client learns
     * which verb to use instead.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.debug("405 method not allowed: {} (supported: {})", ex.getMethod(), ex.getSupportedHttpMethods());
        ProblemDetail pd = problem(HttpStatus.METHOD_NOT_ALLOWED, TYPE_METHOD, "Method Not Allowed",
                "HTTP method %s is not supported by this endpoint".formatted(ex.getMethod()));

        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(pd, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * 415. Carries {@code Accept} listing what the endpoint can consume, for
     * the same discoverability reason as {@code Allow} on a 405.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        log.debug("415 unsupported media type: {}", ex.getContentType());
        ProblemDetail pd = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, TYPE_MEDIA_TYPE, "Unsupported Media Type",
                ex.getContentType() == null
                        ? "A supported Content-Type must be set on the request"
                        : "Content-Type %s is not supported by this endpoint".formatted(ex.getContentType()));

        HttpHeaders headers = new HttpHeaders();
        List<MediaType> supported = ex.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            headers.setAccept(supported);
        }
        return new ResponseEntity<>(pd, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 406 — nothing this endpoint can produce satisfies the Accept header. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail notAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.debug("406 not acceptable; endpoint produces {}", ex.getSupportedMediaTypes());
        return problem(HttpStatus.NOT_ACCEPTABLE, TYPE_NOT_ACCEPT, "Not Acceptable",
                "No supported representation satisfies the Accept header");
    }

    /**
     * 400 — the body could not be parsed (malformed JSON, wrong type for a
     * field, empty body where one is required).
     *
     * <p>The detail is deliberately generic: Jackson's own message names its
     * parser classes and echoes a slice of the offending payload, which is
     * noise for the client and a small information leak in a response body.
     * The full exception is still available at DEBUG.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail malformedBody(HttpMessageNotReadableException ex) {
        log.debug("400 unreadable request body", ex);
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "Bad Request",
                "Request body is missing or is not valid JSON");
    }

    /** 400 — a required query parameter was absent. Names the parameter. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail missingParameter(MissingServletRequestParameterException ex) {
        log.debug("400 missing request parameter: {}", ex.getParameterName());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Validation Failed",
                "Required parameter '%s' is missing".formatted(ex.getParameterName()));
        pd.setProperty("fieldErrors",
                List.of(new FieldErrorView(ex.getParameterName(), "must be provided")));
        return pd;
    }

    /** 400 — a required header was absent. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        log.debug("400 missing request header: {}", ex.getHeaderName());
        return problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Validation Failed",
                "Required header '%s' is missing".formatted(ex.getHeaderName()));
    }

    /**
     * 400 — a parameter was present but could not be converted (e.g.
     * {@code ?size=abc} for an {@code int}). Reported as a field error so the
     * frontend can attach it to the offending input.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail parameterTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String required = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        log.debug("400 parameter type mismatch: {} could not be converted to {}", ex.getName(), required);
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "Validation Failed",
                "Parameter '%s' must be a valid %s".formatted(ex.getName(), required));
        pd.setProperty("fieldErrors",
                List.of(new FieldErrorView(ex.getName(), "must be a valid " + required)));
        return pd;
    }

    /**
     * 400 — remaining request-binding failures. This is the superclass of
     * several of the handlers above; Spring resolves the most specific match,
     * so those still win for their own types and this one only catches what
     * they don't.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ProblemDetail requestBindingFailed(ServletRequestBindingException ex) {
        log.debug("400 request binding failure: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "Bad Request",
                "The request could not be bound to this endpoint");
    }

    /**
     * Any exception that already carries an HTTP status keeps it.
     *
     * <p>Found in production, at the edge. Spring Cloud Gateway signals an
     * unresolvable downstream with
     * {@code NotFoundException: 503 SERVICE_UNAVAILABLE "Unable to find
     * instance for checkout"}, which extends {@link ResponseStatusException}
     * and already carries 503. The {@code Exception.class} catch-all below
     * outranked Spring's own handling of it, so the gateway reported 500 —
     * turning "that service has not registered with Eureka yet" into "the
     * server is broken", and making it retry-eligible into the bargain.
     *
     * <p>Handled generically rather than per exception type: this covers
     * {@code ResponseStatusException}, {@code ErrorResponseException} (its
     * subclass) and every framework or application exception that chooses to
     * carry a status, including ones added later. {@code getHeaders()} is
     * preserved because status-carrying exceptions often supply headers that
     * are part of the contract — {@code Retry-After} on a 503, {@code Allow}
     * on a 405.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> statusCarrying(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();

        // 5xx here is an infrastructure condition (downstream unreachable,
        // upstream timeout), not an unexpected bug, so it is WARN rather than
        // the ERROR-with-stack-trace the catch-all uses. 4xx stays at DEBUG,
        // consistent with the handlers above.
        if (status.is5xxServerError()) {
            log.warn("{} {}: {}", status.value(), ex.getClass().getSimpleName(), ex.getReason());
        } else {
            log.debug("{} {}: {}", status.value(), ex.getClass().getSimpleName(), ex.getReason());
        }

        ProblemDetail pd = problem(status, ex.getReason());
        return new ResponseEntity<>(pd, ex.getHeaders(), status);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unhandled(Exception ex) {
        log.error("500 unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL, "Internal Server Error",
                "An unexpected error occurred. Use the traceId to correlate with logs and traces.");
    }

    /**
     * Builds an RFC 7807 body for an arbitrary status, deriving the title and
     * the problem {@code type} slug from the status reason phrase.
     *
     * <p>That derivation reproduces the explicit constants above exactly —
     * 400 yields {@code bad-request}, 404 {@code not-found}, 409
     * {@code conflict}, 503 {@code service-unavailable} — so a status this
     * advice has never seen before still gets a {@code type} consistent with
     * the rest of the vocabulary instead of {@code about:blank}.
     */
    protected static ProblemDetail problem(HttpStatusCode status, String detail) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String title = resolved != null ? resolved.getReasonPhrase() : "Error " + status.value();
        String slug = title.toLowerCase(Locale.ROOT).replace(' ', '-');

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                status, detail != null ? detail : title);
        pd.setType(URI.create("https://oriah.dev/problems/" + slug));
        pd.setTitle(title);
        attachTraceId(pd);
        return pd;
    }

    /**
     * Builds a fully-populated RFC 7807 body. Centralized so every status this
     * advice produces carries the same four things: our problem {@code type}
     * vocabulary, a human title, a detail, and the traceId.
     *
     * <p>{@code protected} rather than private because this is the method a
     * per-service {@code GlobalExceptionHandler} calls for its own domain
     * exceptions, and it is how every service's error bodies stay identical in
     * shape without each one re-deriving the format.
     */
    protected static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        attachTraceId(pd);
        return pd;
    }

    private static void attachTraceId(ProblemDetail pd) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
    }

    /** RFC 7807 extension carrying one Bean Validation field-level violation. */
    public record FieldErrorView(String field, String message) {}
}
