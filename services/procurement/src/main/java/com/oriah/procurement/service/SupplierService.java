package com.oriah.procurement.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.procurement.dto.CreateSupplierRequest;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.repository.SupplierRepository;

/**
 * Suppliers: add, read, delete (list and edit follow). Every rule about a single
 * supplier lives in the {@link Supplier} aggregate; what lives here is what needs
 * the database: is the name taken, does it exist.
 */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository repository;

    public SupplierService(SupplierRepository repository) {
        this.repository = repository;
    }

    /**
     * Adds a supplier.
     *
     * <p>The name is checked first so the usual duplicate gets a clear 409 that
     * names it. That check and the insert are not one atomic step, though: two
     * requests can both pass it. The partial unique index decides the race, and
     * {@code saveAndFlush} makes the insert (and so the loser's failure) happen
     * here, inside the request, where the procurement advice turns it into the
     * same 409.
     */
    public Supplier create(CreateSupplierRequest request) {
        // Built first: it validates and tidies, so the name checked below is the
        // name that would be stored.
        Supplier supplier = new Supplier(request.name(), request.country(), request.contactName(),
                request.contactDetails(), request.paymentNotes());
        if (repository.existsLiveName(supplier.name())) {
            throw new IllegalStateException("A supplier named '" + supplier.name() + "' already exists");
        }
        return repository.saveAndFlush(supplier);
    }

    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        return live(id);
    }

    /**
     * Deletes a supplier: it disappears from the API and frees its name, and its
     * row is kept (marked deleted) so history can still name it.
     */
    public void delete(UUID id) {
        live(id).markDeleted();
    }

    private Supplier live(UUID id) {
        return repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", "id", id.toString()));
    }
}
