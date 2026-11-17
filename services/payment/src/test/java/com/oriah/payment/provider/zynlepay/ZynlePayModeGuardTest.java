package com.oriah.payment.provider.zynlepay;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.oriah.payment.provider.zynlepay.ZynlePayModeGuard.Mode;
import com.oriah.payment.provider.zynlepay.ZynlePayWire.StatusStyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decided once, when the service starts: which ZynlePay it talks to, and whether that is safe.
 *
 * <p><b>The promise:</b> a stack that is not production cannot take real money, and production cannot run on the stand-in. "Live" is the exact
 * word {@code true} in {@code ORIAH_ZYNLEPAY_LIVE}, read from the process's own environment; no setting in a file can say it, because the config
 * repository is served to every stack. A refusal names the setting at fault and never a value.
 */
class ZynlePayModeGuardTest {

    private static ZynlePaySettings settings(Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>();
        overrides.forEach((key, value) -> properties.put("oriah.payment.zynlepay." + key, value));
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("oriah.payment.zynlepay", ZynlePaySettings.class);
    }

    private static ProcessEnv env(Map<String, String> variables) {
        return variables::get;
    }

    /** Everything a live stack needs in its settings: the wire as it was proved in ZynlePay's sandbox, and the tariff of the merchant agreement. */
    private static Map<String, String> liveSettings() {
        Map<String, String> s = new HashMap<>();
        s.put("wire.api-path", "/zynlepay/jsonapi");
        s.put("wire.status-style", "PATH");
        s.put("wire.status-path", "/zynlepay/paymentstatus");
        s.put("wire.redirect-hosts", "secure.zynlepay.com");
        s.put("wire.return-url", "https://oriah.example/orders/{orderId}/payment?attempt={id}");
        s.put("fees.MTN.percent", "2.0");
        s.put("fees.AIRTEL.percent", "2.0");
        s.put("fees.ZAMTEL.percent", "2.5");
        s.put("fees.CARD.percent", "3.2");
        s.put("fees.CARD.fixed", "1.50");
        return s;
    }

    private static Map<String, String> liveEnv() {
        Map<String, String> e = new HashMap<>();
        e.put("ORIAH_ZYNLEPAY_LIVE", "true");
        e.put("ORIAH_ZYNLEPAY_MERCHANT_ID", "M-REAL");
        e.put("ORIAH_ZYNLEPAY_API_ID", "ID-REAL");
        e.put("ORIAH_ZYNLEPAY_API_KEY", "KEY-REAL-SECRET");
        e.put("ORIAH_ZYNLEPAY_WIRE_CHECKED", "2027-01-15");
        return e;
    }

    private static Map<String, String> without(Map<String, String> map, String key) {
        Map<String, String> copy = new HashMap<>(map);
        copy.remove(key);
        return copy;
    }

    private static Map<String, String> with(Map<String, String> map, String key, String value) {
        Map<String, String> copy = new HashMap<>(map);
        copy.put(key, value);
        return copy;
    }

    // ---------------------------------------------------------------- not live

    @Test
    @DisplayName("with nothing set, payment talks to its own stand-in on this machine, with the stand-in's credentials and placeholder fees")
    void theStandInByDefault() {
        ZynlePaySetup setup = ZynlePayModeGuard.build(env(Map.of()), settings(Map.of()));

        assertThat(setup.mode()).isEqualTo(Mode.SIMULATOR);
        assertThat(setup.wire().baseUrl()).isEqualTo(URI.create("http://localhost:8085"));
        assertThat(setup.wire().apiPath()).isEqualTo("/internal/zynlepay-sim/jsonapi");
        assertThat(setup.wire().statusPath()).isEqualTo("/internal/zynlepay-sim/jsonapi/paymentstatus");
        assertThat(setup.wire().statusStyle()).isEqualTo(StatusStyle.PATH);
        assertThat(setup.wire().plainHttpRedirect()).as("the stand-in's card page is plain http on this machine").isTrue();
        assertThat(setup.wire().redirectHosts()).containsExactly("localhost:8085");
        assertThat(setup.wire().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(setup.wire().readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(setup.tariff("MTN").fee(new BigDecimal("180.00"))).isEqualByComparingTo("4.50");
        assertThat(setup.tariff("CARD").fee(new BigDecimal("180.00"))).isEqualByComparingTo("6.30");
        assertThat(setup.offers("MOBILE_MONEY")).isTrue();
        assertThat(setup.offers("CARD")).isTrue();
        assertThat(setup.wallets().of("+260961234567")).contains("MTN");
        assertThat(setup.schedule().giveUpAfter()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("the stand-in is only ever on this machine or the payment container: any other host is refused")
    void theStandInIsLocalOnly() {
        assertThat(ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("base-url", "http://payment:8085"))).wire().redirectHosts())
                .containsExactly("payment:8085");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("base-url", "https://payments.zynlepay.com"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oriah.payment.zynlepay.base-url");
    }

    @Test
    @DisplayName("a real credential on a stack that is not live is a mistake somebody should see: the start is refused, and the value is not printed")
    void aRealCredentialOnAStackThatIsNotLive() {
        for (String variable : new String[] {"ORIAH_ZYNLEPAY_MERCHANT_ID", "ORIAH_ZYNLEPAY_API_ID", "ORIAH_ZYNLEPAY_API_KEY"}) {
            assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of(variable, "KEY-REAL-SECRET")), settings(Map.of())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(variable).hasMessageNotContaining("KEY-REAL-SECRET");
        }
        // "TRUE", "yes", "1": not the exact word, so not live, and then the credentials beside it are the mistake.
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(with(liveEnv(), "ORIAH_ZYNLEPAY_LIVE", "TRUE")), settings(liveSettings())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("is not live");
    }

    @Test
    @DisplayName("ZynlePay's own sandbox: its fixed address, credentials from the environment, the wire and the fees stated")
    void theSandbox() {
        Map<String, String> sandboxEnv = Map.of("ORIAH_ZYNLEPAY_SANDBOX_MERCHANT_ID", "M-SB", "ORIAH_ZYNLEPAY_SANDBOX_API_ID", "ID-SB",
                "ORIAH_ZYNLEPAY_SANDBOX_API_KEY", "KEY-SB");

        ZynlePaySetup setup = ZynlePayModeGuard.build(env(sandboxEnv), settings(with(liveSettings(), "mode", "SANDBOX")));

        assertThat(setup.mode()).isEqualTo(Mode.SANDBOX);
        assertThat(setup.wire().baseUrl()).isEqualTo(URI.create("https://sandbox.zynlepay.com"));
        assertThat(setup.wire().credentials().apiKey()).isEqualTo("KEY-SB");
        assertThat(setup.wire().plainHttpRedirect()).isFalse();
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(with(liveSettings(), "mode", "SANDBOX"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_ZYNLEPAY_SANDBOX_MERCHANT_ID");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(sandboxEnv), settings(with(without(liveSettings(), "fees.MTN.percent"), "mode", "SANDBOX"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fees.MTN");
    }

    // ---------------------------------------------------------------- live

    @Test
    @DisplayName("live comes only from the process's environment: then it is ZynlePay's production address with the environment's credentials, whatever 'mode' says")
    void liveComesOnlyFromTheEnvironment() {
        ZynlePaySetup setup = ZynlePayModeGuard.build(env(liveEnv()), settings(with(liveSettings(), "mode", "SIMULATOR")));

        assertThat(setup.mode()).isEqualTo(Mode.LIVE);
        assertThat(setup.wire().baseUrl()).isEqualTo(URI.create("https://payments.zynlepay.com"));
        assertThat(setup.wire().apiPath()).isEqualTo("/zynlepay/jsonapi");
        assertThat(setup.wire().statusPath()).isEqualTo("/zynlepay/paymentstatus");
        assertThat(setup.wire().credentials().merchantId()).isEqualTo("M-REAL");
        assertThat(setup.wire().credentials().apiKey()).isEqualTo("KEY-REAL-SECRET");
        assertThat(setup.wire().plainHttpRedirect()).isFalse();
        assertThat(setup.wire().redirectHosts()).containsExactly("secure.zynlepay.com");
        assertThat(setup.tariff("CARD").fee(new BigDecimal("180.00"))).isEqualByComparingTo("7.26");     // 180.00 x 3.2% = 5.76, + 1.50
        // A setting in a file cannot make a stack live.
        assertThat(ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("live", "true"))).mode()).isEqualTo(Mode.SIMULATOR);
        assertThat(ZynlePayModeGuard.simulatorExists(env(liveEnv()), "SIMULATOR")).as("no stand-in beside real credentials").isFalse();
        assertThat(ZynlePayModeGuard.simulatorExists(env(Map.of()), "SIMULATOR")).isTrue();
        assertThat(ZynlePayModeGuard.simulatorExists(env(Map.of()), "SANDBOX")).isFalse();
        assertThat(ZynlePayModeGuard.simulatorExists(env(Map.of()), null)).isTrue();
    }

    @Test
    @DisplayName("a live start is refused when a credential, the sign-off that the wire was checked, a wire setting or a fee is missing")
    void whatALiveStartNeeds() {
        for (String variable : new String[] {"ORIAH_ZYNLEPAY_MERCHANT_ID", "ORIAH_ZYNLEPAY_API_ID", "ORIAH_ZYNLEPAY_API_KEY", "ORIAH_ZYNLEPAY_WIRE_CHECKED"}) {
            assertThatThrownBy(() -> ZynlePayModeGuard.build(env(without(liveEnv(), variable)), settings(liveSettings()))).as(variable)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(variable).hasMessageNotContaining("KEY-REAL-SECRET");
        }
        for (String setting : new String[] {"wire.api-path", "wire.status-style", "wire.status-path", "wire.redirect-hosts", "wire.return-url",
                "fees.MTN.percent", "fees.CARD.percent"}) {
            String named = setting.startsWith("fees.") ? setting.substring(0, setting.lastIndexOf('.')) : setting;
            assertThatThrownBy(() -> ZynlePayModeGuard.build(env(liveEnv()), settings(without(liveSettings(), setting)))).as(setting)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(named);
        }
        // The stand-in's sandbox credentials have no place on a live stack either.
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(with(liveEnv(), "ORIAH_ZYNLEPAY_SANDBOX_API_KEY", "x")), settings(liveSettings())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ORIAH_ZYNLEPAY_SANDBOX_API_KEY");
    }

    @Test
    @DisplayName("live with cards: the customer comes back to an https page of the shop, never to this machine; without cards those two are not asked for")
    void cardsWhenLive() {
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(liveEnv()),
                settings(with(liveSettings(), "wire.return-url", "http://oriah.example/orders/{orderId}/payment?attempt={id}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wire.return-url");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(liveEnv()),
                settings(with(liveSettings(), "wire.return-url", "https://localhost/orders/{orderId}/payment?attempt={id}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wire.return-url");

        Map<String, String> walletsOnly = without(without(without(liveSettings(), "wire.redirect-hosts"), "wire.return-url"), "fees.CARD.percent");
        walletsOnly.remove("fees.CARD.fixed");
        walletsOnly.put("channels", "MOBILE_MONEY");
        ZynlePaySetup setup = ZynlePayModeGuard.build(env(liveEnv()), settings(walletsOnly));
        assertThat(setup.offers("CARD")).isFalse();
        assertThat(setup.offers("MOBILE_MONEY")).isTrue();
    }

    @Test
    @DisplayName("settings that cannot be right stop the start whatever the mode: no channel, a tariff over 20%, codes that overlap, waits that are nothing")
    void settingsThatCannotBeRight() {
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("channels", "")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channels");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("channels", "MOBILE_MONEY,CHEQUE"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHEQUE");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("fees.MTN.percent", "25")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fees.MTN");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("codes.refused", "100")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("poll.give-up-after", "PT1M")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("give-up-after");
        assertThatThrownBy(() -> ZynlePayModeGuard.build(env(Map.of()), settings(Map.of("read-timeout", "PT0S")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-timeout");
    }
}
