package com.oriah.notification.config;

import org.springframework.stereotype.Component;

/** The real environment of the running process. */
@Component
class SystemProcessEnv implements ProcessEnv {

    @Override
    public String get(String name) {
        return System.getenv(name);
    }
}
