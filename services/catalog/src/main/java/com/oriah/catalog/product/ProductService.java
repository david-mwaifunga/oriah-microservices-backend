package com.oriah.catalog.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Optional<Product> findById(UUID id) {
        return repository.findById(id);
    }

    public List<Product> findAll() {
        return repository.findAll();
    }
}
