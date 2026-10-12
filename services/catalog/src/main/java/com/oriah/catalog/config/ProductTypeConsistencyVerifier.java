package com.oriah.catalog.config;

import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.oriah.catalog.model.ProductType;
import com.oriah.catalog.model.ProductTypeRegistry;
import com.oriah.catalog.repository.ProductTypeRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Fails the application context when {@code product_types} and
 * {@link ProductTypeRegistry} disagree. The rule itself, and why both
 * directions matter, is documented on {@link ProductTypeConsistency}.
 *
 * <p>{@code @PostConstruct} rather than an {@code ApplicationRunner}: a
 * runner executes after the context is up, so the service would report itself
 * started and healthy before falling over. Failing during initialisation
 * means a bad deploy never passes its readiness probe.
 *
 * <p>This bean is a plain {@code @Component}, so it loads in full
 * {@code @SpringBootTest} contexts (where Flyway has seeded the rows) and not
 * in {@code @DataJpaTest} or {@code @WebMvcTest} slices, which do not scan
 * components. That is the behaviour we want: the slices have nothing to check.
 */
@Component
@Slf4j
public class ProductTypeConsistencyVerifier {

    private final ProductTypeRepository repository;

    public ProductTypeConsistencyVerifier(ProductTypeRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void verifyOnStartup() {
        var databaseCodes = repository.findAll().stream()
                .map(ProductType::code)
                .collect(Collectors.toSet());

        ProductTypeConsistency.verify(databaseCodes, ProductTypeRegistry.knownCodes());

        log.info("Product types verified: {} codes present in both the database and the registry",
                databaseCodes.size());
    }
}
