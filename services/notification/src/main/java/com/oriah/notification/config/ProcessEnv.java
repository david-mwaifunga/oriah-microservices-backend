package com.oriah.notification.config;

/**
 * The process's own environment, and the ONLY way notification reads it.
 *
 * <p>Some things must not be settable by a commit: whether this stack is live, the mail account, who receives alerts, the pause switch. A Spring
 * property can come from the config repository, which is served to every stack and wins over the service's own file, and it can be changed while
 * the service runs. So those values are never bound as properties. They are read here, from the environment the process was started with.
 */
public interface ProcessEnv {

    /** @return the variable's value, or null when it is not set */
    String get(String name);
}
