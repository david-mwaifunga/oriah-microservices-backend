package com.oriah.events.probe;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A stand-in service for the module's own tests: an empty Spring Boot
 * application that gets the module purely through auto-configuration, the
 * way order, payment or catalog will. It sits in its own package so its
 * component scan sees nothing of the module's main code -- everything the
 * tests use must arrive through the AutoConfiguration.imports file.
 */
@SpringBootApplication
public class ProbeApplication {
}
