package com.oriah.catalog.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class Product {

    private final UUID id;
    private final String title;
    private final String description;
    private final List<String> tags;

    public Product(String title) {
        this(title, null, null);
    }

    public Product(String title, String description) {
        this(title, description, null);
    }

    public Product(String title, String description, List<String> tags) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.id = UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public List<String> tags() {
        return tags;
    }
}
