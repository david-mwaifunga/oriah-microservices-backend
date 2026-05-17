package com.oriah.catalog.product;

import java.util.Optional;

public class Product {

    private final String title;
    private final String description;

    public Product(String title) {
        this(title, null);
    }

    public Product(String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }
}
