package com.oriah.inventory.repository;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.oriah.inventory.TestcontainersConfiguration;
import com.oriah.inventory.model.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class InventoryRepositoryTest {

    @Autowired
    private InventoryRepository repository;

    @Test
    @DisplayName("an inventory row is persisted and retrieved with all fields preserved, kind included")
    void savesAndRetrievesInventory() {
        var variantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var inv = Inventory.open(variantId, "PACKAGING");
        inv.receive(10);
        inv.reserve(3);

        repository.save(inv);
        repository.flush();

        var loaded = repository.findById(variantId).orElseThrow();
        assertThat(loaded.variantId()).isEqualTo(variantId);
        assertThat(loaded.onHand()).isEqualTo(10);
        assertThat(loaded.reserved()).isEqualTo(3);
        assertThat(loaded.available()).isEqualTo(7);
        assertThat(loaded.kind()).isEqualTo("PACKAGING");
    }
}
