package com.oriah.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every catalog test that starts the whole application uses Spring Cloud
 * Stream's in-memory test binder, never the real Kafka binder.
 *
 * <h2>Why</h2>
 *
 * Catalog answers replay requests (MVP_PLAN Phase 1), so the application
 * binds a Kafka listener on {@code oriah.outbox.replay-requests}, and its
 * relay publishes to {@code catalog.product}. With the real binder:
 * <ul>
 *   <li>with no Kafka running, every test context waited about 60 s for that
 *       listener's topic set-up to time out, and 30 s more at shutdown -- the
 *       suite went from about 4 minutes to about 20;</li>
 *   <li>with the development stack running, a test context joined the real
 *       {@code catalog} consumer group, so it could take a real replay request
 *       and answer it from its own throwaway database, and its relay
 *       published test products onto the real {@code catalog.product}.</li>
 * </ul>
 *
 * <p>The test binder has no broker: a test that wants a message reads it from
 * {@code OutputDestination}. Only a slice test ({@code @WebMvcTest},
 * {@code @DataJpaTest}) may leave it out, because a slice starts no binder.
 */
class SpringBootTestsUseTheTestBinderTest {

    @Test
    @DisplayName("every @SpringBootTest in catalog imports the test binder, so none binds to a real Kafka")
    void everySpringBootTestImportsTheTestBinder() {
        List<Class<?>> bootTests = springBootTests();

        // Not vacuous: the scan really finds the suite's application tests.
        assertThat(bootTests).contains(CatalogServiceApplicationTests.class, PublicAccessTest.class);

        List<String> onRealKafka = bootTests.stream()
                .filter(test -> !imports(test).contains(TestChannelBinderConfiguration.class))
                .map(Class::getName)
                .sorted()
                .toList();
        assertThat(onRealKafka).as("@SpringBootTest classes that would bind to a real Kafka").isEmpty();
    }

    /** Every class under com.oriah.catalog carrying @SpringBootTest, directly or through another annotation. */
    private static List<Class<?>> springBootTests() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                // Test classes are package-private and not components; any
                // top-level or static nested class counts.
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));
        return scanner.findCandidateComponents("com.oriah.catalog").stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, SpringBootTestsUseTheTestBinderTest.class.getClassLoader()))
                .toList();
    }

    /** What the test class imports, the way Spring Boot's test support collects it (meta-annotations included). */
    private static Set<Class<?>> imports(Class<?> test) {
        return MergedAnnotations.from(test, SearchStrategy.TYPE_HIERARCHY).stream(Import.class)
                .flatMap(annotation -> Arrays.stream(annotation.getClassArray("value")))
                .collect(Collectors.toSet());
    }
}
