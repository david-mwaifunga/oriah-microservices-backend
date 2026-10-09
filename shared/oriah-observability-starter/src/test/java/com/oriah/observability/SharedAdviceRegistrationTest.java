package com.oriah.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The rule that makes Tier 5 safe: <strong>exactly one
 * {@code @ControllerAdvice} bean per application context</strong>, and the
 * mechanics that keep it true as all 12 services gain one of their own.
 *
 * <h2>What goes wrong without this</h2>
 *
 * <p>Tier 5 gives every service a
 * {@code com.oriah.<service>.exception.GlobalExceptionHandler} extending
 * {@link OriahExceptionHandlerSupport}, so the course's per-service advice
 * convention maps onto this codebase. But this starter already contributes
 * {@link ProblemDetailExceptionHandler} to all 15 modules, and both classes
 * carry {@code @ExceptionHandler(Exception.class)} — one declared, one
 * inherited.
 *
 * <p>Spring does not reject two advices claiming the same exception type. It
 * sorts them with {@code OrderComparator}, and with neither {@code @Order}ed
 * both sit at {@code LOWEST_PRECEDENCE}, leaving the tie to be settled by
 * bean-name ordering. Every service's error responses would then depend on how
 * its advice class happens to be named — the kind of defect that is invisible
 * in review, survives every unit test, and surfaces as "why does catalog
 * return 500 for a validation error when inventory returns 400".
 *
 * <p>So the starter's advice must back off precisely when the module supplies
 * its own. These tests pin that, and pin the two inheritance behaviours the
 * whole design rests on.
 */
class SharedAdviceRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    // ------------------------------------------------------------------
    // Registration: one advice, whichever module we are in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a module with no advice of its own gets the shared default")
    void defaultAdviceIsRegisteredWhenTheModuleHasNoneOfItsOwn() {
        // The gateway, configserver and eurekaserver are in this position:
        // no domain exceptions, so no reason to write an advice, but they
        // still need the RFC 7807 contract. The gateway in particular needs
        // the statusCarrying handler -- that is what stops an unresolvable
        // lb:// target reporting 500 instead of 503.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ProblemDetailExceptionHandler.class);
            assertThat(ctx.getBeanNamesForAnnotation(ControllerAdvice.class))
                    .as("exactly one advice bean")
                    .hasSize(1);
        });
    }

    @Test
    @DisplayName("a module that declares its own advice does NOT also get the shared one")
    void defaultAdviceBacksOffWhenTheModuleDeclaresItsOwn() {
        // The Tier 5 case, x12. ServiceOwnAdvice stands in for
        // com.oriah.catalog.exception.GlobalExceptionHandler.
        contextRunner.withUserConfiguration(ServiceOwnAdvice.class).run(ctx -> {
            assertThat(ctx.getBeanNamesForAnnotation(ControllerAdvice.class))
                    .as("two advices both claiming Exception.class would make the "
                            + "winner a function of bean-name ordering")
                    .hasSize(1);
            assertThat(ctx).doesNotHaveBean(ProblemDetailExceptionHandler.class);
            assertThat(ctx).hasSingleBean(ServiceOwnAdvice.class);
        });
    }

    @Test
    @DisplayName("it backs off for an advice that does NOT extend the shared base class either")
    void defaultAdviceBacksOffForAnUnrelatedAdvice() {
        // Distinguishes two candidate conditions. Keying the back-off on the
        // shared SUPERTYPE would miss this bean and register a second advice;
        // keying it on the @ControllerAdvice ANNOTATION catches it. The
        // annotation is the right semantics -- "this context already has an
        // advice" -- and a service is free to write one that does not extend
        // ours (or to add one for @ModelAttribute binding and nothing else).
        //
        // That second case is the sharp edge worth knowing about: an advice
        // added for an unrelated purpose silently takes over error rendering,
        // because this starter then contributes nothing. Hence 5-pre3's
        // per-service guard test asserts the advice bean is the EXPECTED one,
        // not merely that there is exactly one.
        contextRunner.withUserConfiguration(UnrelatedAdvice.class).run(ctx -> {
            assertThat(ctx.getBeanNamesForAnnotation(ControllerAdvice.class)).hasSize(1);
            assertThat(ctx).doesNotHaveBean(ProblemDetailExceptionHandler.class);
        });
    }

    // ------------------------------------------------------------------
    // Inheritance: the two behaviours the design depends on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("handlers inherited from the abstract base are discovered on a subclass advice")
    void inheritedHandlersAreDiscoveredOnASubclassAdvice() throws Exception {
        // Load-bearing, and not obvious: the @ExceptionHandler methods are
        // declared on OriahExceptionHandlerSupport, which carries no
        // annotation and is never a bean. If ExceptionHandlerMethodResolver
        // only looked at methods DECLARED on the advice class, every
        // per-service GlobalExceptionHandler would silently lose all 14
        // inherited handlers -- framework errors would fall through to
        // DefaultHandlerExceptionResolver (plain status, empty body, no
        // traceId) and domain exceptions would render as Boot's default error
        // page. Asserted rather than assumed.
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new ServiceOwnAdvice())
                .build();

        MvcResult result = mockMvc.perform(post("/probe/get-only")).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("405 comes from an INHERITED handler; 500 would mean the "
                        + "inherited catch-all claimed it, and anything else that "
                        + "the inherited handlers were not found at all")
                .isEqualTo(405);
        assertThat(result.getResponse().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getHeader("Allow"))
                .as("the inherited 405 handler sets Allow")
                .contains("GET");
    }

    @Test
    @DisplayName("a subclass handler for a specific type beats the inherited catch-all")
    void aSubclassHandlerWinsOverTheInheritedCatchAll() throws Exception {
        // The other half: a service's own domain exception must reach the
        // service's own handler rather than the inherited
        // @ExceptionHandler(Exception.class). This is exactly the resolution
        // order that bit us in Tier 0.5 -- there the catch-all outranked
        // Spring's DefaultHandlerExceptionResolver and flattened 405/415/406
        // to 500. Within a single advice, most-specific wins regardless of
        // which class in the hierarchy declared the method, so a per-service
        // handler is safe. Proving it here is what makes the pattern
        // reusable 12 times without re-testing the mechanism each time.
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(new ServiceOwnAdvice())
                .build();

        MvcResult result = mockMvc.perform(get("/probe/domain-failure")).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("418 is the subclass's own mapping; 500 would mean the "
                        + "inherited Exception.class handler claimed it")
                .isEqualTo(418);
        assertThat(result.getResponse().getContentAsString())
                .contains("https://oriah.dev/problems/domain-specific");
    }

    @Test
    @DisplayName("the shared base class is abstract and unannotated")
    void theSharedBaseClassCannotItselfBecomeAnAdvice() {
        // A one-line guard against the most likely future regression: someone
        // adds @RestControllerAdvice to the base class so that "it just works
        // everywhere", and every service context quietly gains a second
        // advice. Component scanning would pick the base class up in any
        // module whose scan covers com.oriah.observability.
        assertThat(java.lang.reflect.Modifier.isAbstract(
                OriahExceptionHandlerSupport.class.getModifiers()))
                .as("OriahExceptionHandlerSupport must stay abstract")
                .isTrue();
        assertThat(OriahExceptionHandlerSupport.class
                .getAnnotation(ControllerAdvice.class))
                .as("annotating the shared base class registers it as a SECOND "
                        + "advice in every service that also has its own; put the "
                        + "annotation on the leaf, as ProblemDetailExceptionHandler does")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Stands in for a per-service {@code exception/GlobalExceptionHandler}. */
    @RestControllerAdvice
    static class ServiceOwnAdvice extends OriahExceptionHandlerSupport {

        @ExceptionHandler(DomainFailure.class)
        public ProblemDetail domainFailure(DomainFailure ex) {
            return problem(HttpStatus.I_AM_A_TEAPOT,
                    java.net.URI.create("https://oriah.dev/problems/domain-specific"),
                    "Domain Failure", ex.getMessage());
        }
    }

    /**
     * An advice that does not extend the shared base class, standing in for one
     * added for an unrelated reason.
     */
    @RestControllerAdvice
    static class UnrelatedAdvice {

        @ExceptionHandler(IllegalStateException.class)
        public ProblemDetail somethingElse(IllegalStateException ex) {
            return ProblemDetail.forStatus(HttpStatus.CONFLICT);
        }
    }

    static class DomainFailure extends RuntimeException {
        DomainFailure(String message) {
            super(message);
        }
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/get-only")
        String getOnly() {
            return "ok";
        }

        @GetMapping("/domain-failure")
        String domainFailure() {
            throw new DomainFailure("the service's own exception type");
        }
    }
}
