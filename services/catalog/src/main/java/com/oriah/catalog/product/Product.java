package com.oriah.catalog.product;

public class Product {

    public Product(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }
}
