package com.oriah.notification.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Notification's settings for a test that needs no Spring context: the SHIPPED file ({@code src/main/resources/application.yaml}) with a test's
 * own values laid over it. A pure test therefore runs against the defaults the service really ships, and a default that is missing from the file
 * fails here rather than at the first start.
 */
public final class TestSettings {

    private TestSettings() {
    }

    public static NotificationSettings shipped() {
        return shippedWith(Map.of());
    }

    public static NotificationSettings shippedWith(Map<String, String> overrides) {
        MockEnvironment environment = new MockEnvironment();
        overrides.forEach(environment::setProperty);
        try {
            // The main file, not the test one: the root application.yaml on the classpath (main resources come first on a test classpath).
            for (PropertySource<?> document : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
                environment.getPropertySources().addLast(document);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Binder.get(environment).bind("oriah.notification", NotificationSettings.class).get();
    }
}
