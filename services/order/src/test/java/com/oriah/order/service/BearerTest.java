package com.oriah.order.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who bears the charge for sending a refund (Phase 12, plan 5.2 and FINAL AM3): a setting per reason, read once at start. A reason with no setting
 * refuses to start, so nobody ever bears a charge by accident.
 */
class BearerTest {

    private static RefundBearers bearers(Map<String, String> cancellation, Map<String, String> returns) {
        RefundBearers b = new RefundBearers();
        b.setCancellation(cancellation);
        b.setReturn(returns);
        return b;
    }

    private static Map<String, String> all(String bearer, String... reasons) {
        Map<String, String> map = new HashMap<>();
        for (String reason : reasons) {
            map.put(reason, bearer);
        }
        return map;
    }

    private static final String[] CANCELLATION = {"CUSTOMER_ASKED", "PIECE_UNAVAILABLE", "SHOP_ERROR", "OTHER"};
    private static final String[] RETURN = {"CHANGED_MIND", "WRONG_SIZE", "FAULTY", "NOT_AS_DESCRIBED", "WRONG_ITEM_SENT", "DAMAGED_IN_TRANSIT", "NOT_DELIVERED",
            "OTHER"};

    @Test
    void theBearerIsReadPerKindAndReason() {
        Map<String, String> cancellation = all("CUSTOMER", CANCELLATION);
        cancellation.put("SHOP_ERROR", "ORIAH");
        RefundBearers b = bearers(cancellation, all("CUSTOMER", RETURN));
        b.validate();

        assertThat(b.forCancellation("SHOP_ERROR")).isEqualTo("ORIAH");
        assertThat(b.forCancellation("CUSTOMER_ASKED")).isEqualTo("CUSTOMER");
        assertThat(b.forReturn("FAULTY")).isEqualTo("CUSTOMER");
        assertThat(RefundBearers.CANCELLATION_REASONS).containsExactlyInAnyOrder(CANCELLATION);
        assertThat(RefundBearers.RETURN_REASONS).containsExactlyInAnyOrder(RETURN);
    }

    @Test
    void aReasonWithNoSettingRefusesToStart() {
        Map<String, String> returns = all("CUSTOMER", RETURN);
        returns.remove("NOT_DELIVERED");

        assertThatThrownBy(() -> bearers(all("CUSTOMER", CANCELLATION), returns).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.refunds.bearer.return.NOT_DELIVERED");
        assertThatThrownBy(() -> bearers(null, all("CUSTOMER", RETURN)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.refunds.bearer.cancellation.");
    }

    @Test
    void anythingButTheTwoBearersRefusesToStart() {
        Map<String, String> cancellation = all("CUSTOMER", CANCELLATION);
        cancellation.put("OTHER", "customer");

        assertThatThrownBy(() -> bearers(cancellation, all("CUSTOMER", RETURN)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.refunds.bearer.cancellation.OTHER").hasMessageContaining("CUSTOMER or ORIAH");
    }

    @Test
    void aReasonNobodyKnowsIsNeverGivenABearer() {
        RefundBearers b = bearers(all("CUSTOMER", CANCELLATION), all("CUSTOMER", RETURN));

        assertThatThrownBy(() -> b.forCancellation("CHANGED_MIND")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.forReturn("PIECE_UNAVAILABLE")).isInstanceOf(IllegalArgumentException.class);
    }

    /** The file the service ships with: the customer bears the charge for every reason (CLIENT_DECISIONS: the fee is taken from the refund). */
    @Test
    void theShippedSettingsNameEveryReasonAndTheCustomerBearsEachOne() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties shipped = yaml.getObject();

        for (String reason : CANCELLATION) {
            assertThat(shipped.getProperty("oriah.refunds.bearer.cancellation." + reason)).as(reason).isEqualTo("CUSTOMER");
        }
        for (String reason : RETURN) {
            assertThat(shipped.getProperty("oriah.refunds.bearer.return." + reason)).as(reason).isEqualTo("CUSTOMER");
        }
    }
}
