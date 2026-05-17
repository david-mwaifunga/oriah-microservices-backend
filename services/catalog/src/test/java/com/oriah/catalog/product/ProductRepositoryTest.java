package com.oriah.catalog.product;

import java.util.List;

import com.oriah.catalog.TestcontainersConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ProductRepositoryTest {

    @Autowired
    private ProductRepository repository;

    @Test
    @DisplayName("a product is persisted and retrieved with all fields preserved")
    void savesAndRetrievesAProduct() {
        var aurelia = new Product(
                "Aurelia Solitaire Ring",
                "A single-stone solitaire in 18k recycled gold.",
                List.of("rings", "engagement", "18k"));

        repository.save(aurelia);
        repository.flush();

        var loaded = repository.findById(aurelia.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(aurelia.id());
        assertThat(loaded.title()).isEqualTo("Aurelia Solitaire Ring");
        assertThat(loaded.description()).hasValue("A single-stone solitaire in 18k recycled gold.");
        assertThat(loaded.tags()).containsExactlyInAnyOrder("rings", "engagement", "18k");
    }
}
