package com.oriah.catalog.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ResourceUtils;

/**
 * Catalog's own configuration files -- {@code application.yaml} and every
 * profile file beside it ({@code application-docker.yaml},
 * {@code application-local.yaml}, and any added later) -- read from the
 * compiled classes, exactly as Spring Boot finds them.
 *
 * <p>Only catalog's own: files inside a library jar, and any a test might
 * add under {@code src/test/resources}, are left out.
 */
final class CatalogConfigFiles {

    private static final Pattern PROFILE_FILE = Pattern.compile("application-(.+)\\.(yaml|yml|properties)");

    private CatalogConfigFiles() {
    }

    /** Every one of catalog's configuration files. */
    static List<Resource> all() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>();
        try {
            for (String pattern : List.of("classpath*:application*.yaml", "classpath*:application*.yml",
                    "classpath*:application*.properties")) {
                for (Resource file : resolver.getResources(pattern)) {
                    URL url = file.getURL();
                    if (ResourceUtils.isFileURL(url) && !url.getPath().contains("/test-classes/")) {
                        files.add(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** The profiles catalog ships a file for: {@code application-docker.yaml} gives {@code docker}. */
    static Set<String> profiles() {
        Set<String> profiles = new TreeSet<>();
        for (Resource file : all()) {
            Matcher name = PROFILE_FILE.matcher(file.getFilename());
            if (name.matches()) {
                profiles.add(name.group(1));
            }
        }
        return profiles;
    }

    /** The file's settings, one property source per YAML document, loaded as Spring Boot loads them. */
    static List<PropertySource<?>> load(Resource file) {
        PropertySourceLoader loader = file.getFilename().endsWith(".properties")
                ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
        try {
            return loader.load(file.getFilename(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
