package com.oriah.observability;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The default error advice, for modules that have no domain exceptions of
 * their own.
 *
 * <p>Empty on purpose: every handler lives in
 * {@link OriahExceptionHandlerSupport}, and this class exists only to carry
 * the {@code @RestControllerAdvice} annotation that turns that contract into a
 * registered advice bean.
 *
 * <p>{@link ObservabilityAutoConfiguration} registers it <strong>only when the
 * consuming module declares no {@code @ControllerAdvice} of its own</strong>.
 * That condition is what keeps the rule "exactly one advice per context" true
 * as the 12 services each gain a
 * {@code com.oriah.<service>.exception.GlobalExceptionHandler}: where a
 * service supplies its own, this one backs off; where it does not — the
 * gateway, configserver, eurekaserver — this one is the advice. Without the
 * condition both would register, both would claim the inherited
 * {@code @ExceptionHandler(Exception.class)}, and which one won would come
 * down to bean-name ordering.
 *
 * <p>The name is unchanged from when this class held the handlers directly,
 * so the 20 starter tests that drive the contract through
 * {@code setControllerAdvice(new ProblemDetailExceptionHandler())} still
 * exercise it — and still passing after the extraction is the evidence that
 * the extraction changed no behaviour.
 *
 * @see OriahExceptionHandlerSupport for the status mapping and the reasons it
 *      is abstract, unannotated, and not a {@code ResponseEntityExceptionHandler}
 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler extends OriahExceptionHandlerSupport {
}
