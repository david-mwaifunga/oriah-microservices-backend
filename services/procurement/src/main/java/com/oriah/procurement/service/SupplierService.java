package com.oriah.procurement.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
     * One page of live suppliers, ordered by name ignoring letter case.
     *
     * @param active {@code null} for both, otherwise only suppliers we are
     *               using ({@code true}) or have stopped using ({@code false})
     * @param q      text the name must contain, ignoring letter case; blank means
     *               no filter. {@code %}, {@code _} and {@code !} are ordinary
     *               characters here, not wildcards.
     */
    @Transactional(readOnly = true)
    public Page<Supplier> list(int page, int size, Boolean active, String q) {
        Pageable pageable = PageRequest.of(page, size);
        String text = q == null ? "" : q.strip();
        if (text.isEmpty()) {
            return active == null ? repository.findLive(pageable) : repository.findLiveByActive(active, pageable);
        }
        String pattern = "%" + escapeForLike(text) + "%";
        return active == null
                ? repository.findLiveByName(pattern, pageable)
                : repository.findLiveByActiveAndName(active, pattern, pageable);
    }

    /** '!' is the repository's escape character: put it before the three that would otherwise act. */
    private static String escapeForLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
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
