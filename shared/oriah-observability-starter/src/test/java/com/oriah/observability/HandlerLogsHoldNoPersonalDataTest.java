package com.oriah.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What the shared exception handler writes to the log when a request is refused (Phase 9). A phone order carries a caller's name, phone and address, and
 * Zambia's Data Protection Act 2021 applies: a rejected value must not reach a log line, at ANY level. The client's own answer names the field and the
 * rule, never the value; the log now says the same and no more. (This also covers Phase 8's recipient fields, which travel the same way.)
 */
class HandlerLogsHoldNoPersonalDataTest {

    private static final String PHONE = "0971 234 567 secret";
    private static final String NAME = "Mulenga Chanda";

    record Body(@Pattern(regexp = "^\\+260[0-9]{9}$", message = "must be a Zambian phone number") String phone) {
    }

    @RestController
    static class Door {
        @PostMapping("/door")
        String take(@Valid @RequestBody Body body) {
            return "ok";
        }

        @PostMapping("/bad-argument")
        String bad(@RequestBody String raw) {
            throw new IllegalArgumentException("the caller " + NAME + " at " + PHONE + " is not valid");
        }
    }

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(OriahExceptionHandlerSupport.class);
    private Level before;

    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    private MockMvc mvc() {
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new Door()).setControllerAdvice(new ProblemDetailExceptionHandler()).setValidator(validator).build();
    }

    @BeforeEach
    void capture() {
        before = handlerLog.getLevel();
        handlerLog.setLevel(Level.TRACE);
        lines.start();
        handlerLog.addAppender(lines);
    }

    @AfterEach
    void release() {
        handlerLog.detachAppender(lines);
        handlerLog.setLevel(before);
        lines.stop();
    }

    private String everythingLogged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(lines.list)) {
            all.append(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                all.append(' ').append(e.getThrowableProxy().getMessage());
                for (var frame : e.getThrowableProxy().getStackTraceElementProxyArray()) {
                    all.append(' ').append(frame.getSTEAsString());
                }
            }
        }
        return all.toString();
    }

    @Test
    @DisplayName("a refused field is logged by its name and rule, never by the value that was typed")
    void aRejectedValueIsNotLogged() throws Exception {
        mvc().perform(post("/door").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + PHONE + "\"}")).andExpect(status().isBadRequest());

        assertThat(everythingLogged()).contains("phone").doesNotContain(PHONE).doesNotContain("0971");
    }

    @Test
    @DisplayName("an unreadable body is logged by its kind of failure, never with the slice of the payload the parser echoes")
    void anUnreadableBodyIsNotEchoed() throws Exception {
        mvc().perform(post("/door").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + PHONE + "\", \"name\": \"" + NAME + "\" ")).andExpect(status().isBadRequest());

        assertThat(everythingLogged()).doesNotContain(PHONE).doesNotContain(NAME).doesNotContain("0971");
    }

    @Test
    @DisplayName("an illegal argument's message may name a person: the client is answered with it, the log carries only that it happened")
    void anIllegalArgumentMessageIsNotLogged() throws Exception {
        mvc().perform(post("/bad-argument").contentType(MediaType.APPLICATION_JSON).content("\"x\"")).andExpect(status().isBadRequest());

        assertThat(everythingLogged()).contains("IllegalArgumentException").doesNotContain(PHONE).doesNotContain(NAME);
    }
}
