package com.oriah.catalog.product;

public class Product {

    private final String title;

    public Product(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        this.title = title;
    }

    public String title() {
        return title;
    }
}
