package com.oriah.notification;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * A test of the whole service on a real Postgres: events go in through Spring Cloud Stream's test binder, HTTP goes in through MockMvc, and the
 * clock stands still ({@link MutableClock}).
 *
 * <p>Every test class that carries this annotation asks for exactly the same Spring context, so they all share ONE (and one database
 * container) instead of each starting its own, which matters on a slow machine. That is why the properties and imports live here and not on
 * each class: one stray property on one class would quietly give it a context, and a container, of its own. Each test starts with
 * {@code TestData.clean}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=orderEvents;paymentEvents",
        "spring.cloud.stream.bindings.orderEvents-in-0.destination=oriah.order",
        "spring.cloud.stream.bindings.paymentEvents-in-0.destination=oriah.payment"
})
public @interface ServiceTest {
}
