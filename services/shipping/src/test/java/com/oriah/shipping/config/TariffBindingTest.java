package com.oriah.shipping.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import com.oriah.shipping.model.ShippingOption;
import com.oriah.shipping.service.ShippingService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping's published configuration, as the real {@code application.yaml}
 * binds it (MVP_PLAN Phase 0, item 1).
 *
 * <p>The zone rate card is gone: it was an old USD list multiplied by 25 that
 * nobody chose, and the owner prices delivery per town in Phase 7. What is
 * left is the currency the service quotes in -- and the guarantee that a
 * broken configuration stops the service at start-up rather than quoting from
 * it. That guarantee matters because checkout used to treat a shipping outage
 * as free delivery.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.oriah.shipping.TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class TariffBindingTest {

    private static final String PREFIX = "oriah.shipping.tariff.";

    @Autowired
    private ShippingTariff tariff;

    @Autowired
    private ShippingService service;

    @EnableConfigurationProperties(ShippingTariff.class)
    static class TariffHolder {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(TariffHolder.class);
    }

    @Test
    @DisplayName("the shipped configuration carries no zone rate card")
    void theShippedYamlCarriesNoZoneCard() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        List<String> zoneKeys = sources.stream()
                .flatMap(s -> List.of(((EnumerablePropertySource<?>) s).getPropertyNames()).stream())
                .filter(k -> k.startsWith(PREFIX + "domestic")
                        || k.startsWith(PREFIX + "regional")
                        || k.startsWith(PREFIX + "international"))
                .toList();
        assertThat(zoneKeys).isEmpty();
    }

    @Test
    @DisplayName("a currency alone is a complete configuration")
    void aCurrencyAloneStarts() {
        runner().withPropertyValues(PREFIX + "currency=ZMW")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("no currency refuses to start the service")
    void aMissingCurrencyFailsTheContext() {
        runner().run(ctx -> assertThat(ctx)
                .as("quoting with no currency is worse than not starting")
                .hasFailed());
    }

    @Test
    @DisplayName("a currency that is not a 3-letter code refuses to start the service")
    void aMalformedCurrencyFailsTheContext() {
        runner().withPropertyValues(PREFIX + "currency=ZMWX")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("a leftover zone card -- say, a stale override in config-repo -- refuses to start the service")
    void aLeftoverZoneCardFailsTheContext() {
        runner().withPropertyValues(PREFIX + "currency=ZMW", PREFIX + "domestic.standard-cost=125.00")
                .run(ctx -> assertThat(ctx)
                        .as("a stale K125 that silently stopped applying would look configured "
                                + "and price nothing; refusing makes it visible")
                        .hasFailed());
    }

    @Test
    @DisplayName("the shipped configuration quotes in the brand currency")
    void theShippedCardIsInKwacha() {
        assertThat(tariff.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("the service, booted from the shipped configuration, quotes self-collect at no charge")
    void theServiceQuotesSelfCollectFromTheShippedYaml() {
        var options = service.quote("ZM", new BigDecimal("100.00"), "ZMW", null).options();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).code()).isEqualTo(ShippingOption.SELF_COLLECT);
        assertThat(options.get(0).cost()).isEqualByComparingTo("0.00");
        assertThat(options.get(0).currency()).isEqualTo("ZMW");
    }
}
