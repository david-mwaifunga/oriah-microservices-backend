package com.oriah.payment.provider.zynlepay;

/**
 * The process's own environment, and the ONLY way payment reads what must not be settable by a commit: whether this stack takes real money, and
 * who Oriah is to ZynlePay.
 *
 * <p>A Spring property can come from the config repository, which is served to every stack and wins over the service's own file. So those values
 * are never bound as properties. They are read here, from the environment the process was started with.
 */
public interface ProcessEnv {

    /** @return the variable's value, or null when it is not set */
    String get(String name);
}
