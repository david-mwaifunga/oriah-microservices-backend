package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.dto.ProductUpdateRequest;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.repository.ProductRepository;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Product> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Returns a page of products, optionally filtered by a single tag.
     * Null {@code tag} means "no filter."
     */
    @Transactional(readOnly = true)
    public Page<Product> findAll(String tag, Pageable pageable) {
        return repository.findOptionallyFilteredByTag(tag, pageable);
    }

    /**
     * Creates and persists a new product. Title is required and validated by
     * the {@link Product} domain rule; description and tags are optional.
     * The repository assigns no id of its own — {@link Product} generates a
     * UUID at construction.
     */
    @Transactional
    public Product create(String title, String description, List<String> tags) {
        Product product = new Product(title, description, tags);
        return repository.save(product);
    }

    /**
     * Applies a partial update to an existing product. Returns
     * {@link Optional#empty()} when no product exists with the given id.
     *
     * <p>Null fields in {@code request} are treated as "do not touch."
     * Non-null fields replace the corresponding entity field. Changes are
     * persisted automatically by JPA dirty checking because this method
     * runs inside a {@code @Transactional} boundary and the entity is in
     * managed state.
     */
    @Transactional
    public Optional<Product> update(UUID id, ProductUpdateRequest request) {
        return repository.findById(id).map(product -> {
            if (request.title() != null) {
                product.updateTitle(request.title());
            }
            if (request.description() != null) {
                product.updateDescription(request.description());
            }
            if (request.tags() != null) {
                product.updateTags(request.tags());
            }
            return product;
        });
    }

    /**
     * Deletes the product with the given id. Returns true if a product was
     * found and removed, false if no product had that id. The two-step
     * lookup-then-delete lets the controller return 204 vs 404 unambiguously
     * — {@code repository.deleteById} is silent when the id is missing.
     */
    @Transactional
    public boolean deleteById(UUID id) {
        return repository.findById(id)
                .map(product -> {
                    repository.delete(product);
                    return true;
                })
                .orElse(false);
    }
}
