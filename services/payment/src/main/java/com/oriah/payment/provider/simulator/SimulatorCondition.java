package com.oriah.payment.provider.simulator;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.oriah.payment.provider.zynlepay.ZynlePayModeGuard;

/**
 * Whether the stand-in for ZynlePay is part of this process: decided by {@link ZynlePayModeGuard#simulatorExists}, from the same two facts the
 * guard starts the service on (the process's own environment says "not live", and the mode is SIMULATOR). Everything of the stand-in hangs on
 * this one condition (its engine, its doors and the rule that opens them), so none of it can exist beside real credentials.
 */
public class SimulatorCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return ZynlePayModeGuard.simulatorExists(System::getenv, context.getEnvironment().getProperty("oriah.payment.zynlepay.mode"));
    }
}
