package com.oriah.payment;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;

/**
 * Lets a test start payment with the {@code docker} profile, which needs a
 * Config Server that a test does not have.
 *
 * <p>Why it is needed: application-docker.yaml imports
 * {@code configserver:http://configserver:8888} <em>without</em>
 * {@code optional:} -- in the compose stack the Config Server is a hard
 * dependency, and payment rightly refuses to start without it. A test has no
 * Config Server, and no machine called {@code configserver}, so that import
 * would stop the context before anything else could be checked.
 *
 * <p>What it does: a test that sets {@code spring.cloud.config.enabled=false}
 * switches Spring Cloud's own handling of {@code configserver:} imports off,
 * and Spring Boot would then refuse an import nothing can read. In that case,
 * and only for an import that is not optional, this stand-in takes it and
 * loads nothing -- as if the Config Server had answered with no settings for
 * payment. Everything else is handled exactly as before: every test that
 * leaves the Config Server on, and the {@code optional:configserver:} import
 * in application.yaml (ContactInfoTest and ErrorAdviceRegistrationTest switch
 * the Config Server off too, and start with that optional import alone).
 *
 * <p>Registered in src/test/resources/META-INF/spring.factories, so it exists
 * in tests only.
 */
public final class ConfigServerStandIn {

    private static final String PREFIX = "configserver:";

    private ConfigServerStandIn() {
    }

    /** Takes a required {@code configserver:} import, when a test has switched the Config Server off. */
    public static final class Resolver implements ConfigDataLocationResolver<Import> {

        @Override
        public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            boolean configServerOff = !context.getBinder().bind("spring.cloud.config.enabled", Boolean.class)
                    .orElse(true);
            return configServerOff && location.hasPrefix(PREFIX) && !location.isOptional();
        }

        @Override
        public List<Import> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
            return List.of(new Import(location.getValue()));
        }
    }

    /** Loads nothing for it: no settings, as from a Config Server that has none for this service. */
    public static final class Loader implements ConfigDataLoader<Import> {

        @Override
        public ConfigData load(ConfigDataLoaderContext context, Import resource) {
            return ConfigData.EMPTY;
        }
    }

    /** One {@code configserver:} import, taken by the stand-in. */
    public static final class Import extends ConfigDataResource {

        private final String location;

        Import(String location) {
            this.location = location;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Import that && location.equals(that.location);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location);
        }

        @Override
        public String toString() {
            return "Config Server stand-in for " + location + " (no settings)";
        }
    }
}
