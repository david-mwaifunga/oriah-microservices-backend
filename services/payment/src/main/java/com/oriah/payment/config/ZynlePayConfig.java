package com.oriah.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.oriah.payment.provider.PaymentProvider;
import com.oriah.payment.provider.simulator.SimulatorCondition;
import com.oriah.payment.provider.simulator.ZynlePaySimulatorEngine;
import com.oriah.payment.provider.zynlepay.ProcessEnv;
import com.oriah.payment.provider.zynlepay.ZynlePayAdapter;
import com.oriah.payment.provider.zynlepay.ZynlePayModeGuard;
import com.oriah.payment.provider.zynlepay.ZynlePaySettings;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

/**
 * Collecting through ZynlePay, as this process does it (Phase 13). {@link ZynlePayModeGuard} decides once, at start, which ZynlePay that is,
 * and the service does not start when the answer would be unsafe.
 */
@Configuration
public class ZynlePayConfig {

    /** The real environment of the running process: the only place "live" and the credentials are read from. */
    @Bean
    ProcessEnv processEnv() {
        return System::getenv;
    }

    @Bean
    ZynlePaySetup zynlePaySetup(ProcessEnv env, ZynlePaySettings settings) {
        return ZynlePayModeGuard.build(env, settings);
    }

    /** The one provider: the same adapter whichever ZynlePay it talks to (the stand-in speaks ZynlePay's own JSON). */
    @Bean
    PaymentProvider paymentProvider(ZynlePaySetup setup) {
        return new ZynlePayAdapter(setup.wire(), setup.codes());
    }

    @Bean
    @Conditional(SimulatorCondition.class)
    ZynlePaySimulatorEngine zynlePaySimulatorEngine(ZynlePaySetup setup) {
        ZynlePaySettings.Sim sim = setup.settings().sim();
        return new ZynlePaySimulatorEngine(sim.merchantId(), sim.apiId(), sim.apiKey(), setup.wire().baseUrl() + ZynlePayModeGuard.SIMULATOR_PATH);
    }

    /**
     * The stand-in's doors need no token (ZynlePay would send none), and this opens nothing else: it applies to their path only, and it exists
     * only where the stand-in does. The gateway refuses everything under {@code /internal} to everyone outside.
     */
    @Bean
    @Order(1)
    @Conditional(SimulatorCondition.class)
    SecurityFilterChain zynlePaySimulatorChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(ZynlePayModeGuard.SIMULATOR_PATH + "/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
