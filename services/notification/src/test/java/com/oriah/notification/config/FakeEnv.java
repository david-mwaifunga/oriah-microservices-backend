package com.oriah.notification.config;

import java.util.HashMap;
import java.util.Map;

/**
 * The process environment as a test wants it. The real one ({@link SystemProcessEnv}) reads {@code System.getenv}, which a test cannot set.
 */
public final class FakeEnv implements ProcessEnv {

    private final Map<String, String> values = new HashMap<>();

    public FakeEnv with(String name, String value) {
        values.put(name, value);
        return this;
    }

    public FakeEnv without(String name) {
        values.remove(name);
        return this;
    }

    @Override
    public String get(String name) {
        return values.get(name);
    }
}
