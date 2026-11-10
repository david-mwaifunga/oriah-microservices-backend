package com.oriah.procurement.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.OriahExceptionHandlerSupport.FieldErrorView;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.procurement.dto.CreateSupplierRequest;
import com.oriah.procurement.dto.UpdateSupplierRequest;
import com.oriah.procurement.exception.InvalidFieldsException;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.repository.SupplierRepository;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Suppliers: add, read, list, edit, delete. Every rule about a single
 * supplier lives in the {@link Supplier} aggregate; what lives here is what needs
 * the database: is the name taken, does it exist.
 */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository repository;
    private final Validator validator;

    public SupplierService(SupplierRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
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
     * Changes the fields that were sent and leaves the rest alone.
     *
     * <p>Every field that was sent is checked first, against the same rules as
     * adding a supplier, and all the problems are reported together; nothing is
     * looked up or changed until they are fixed. A field that was left out is not
     * checked: whatever is stored passed the rules when it was stored.
     *
     * <p>If the request carries the version it was made from and the supplier has
     * moved on since, nothing is applied: it is refused as a lost update (409
     * {@code concurrent-update}), the same answer as two edits colliding inside the
     * database. The check comes before any change is looked at.
     *
     * <p>The name is compared with every <em>other</em> live supplier, so a supplier
     * can change the letter case of its own name. As on adding, that check and the
     * write are not one atomic step: the unique index decides a race and
     * {@code saveAndFlush} makes the loser fail here, inside the request, so the
     * procurement advice answers it as a 409. The {@code @Version} column does the
     * same for two edits of one supplier at once (409 {@code concurrent-update}).
     * An edit that changes nothing writes nothing, so the version does not move.
     */
    public Supplier update(UUID id, UpdateSupplierRequest request) {
        rejectInvalidFields(request);
        Supplier supplier = live(id);
        if (request.hasVersion() && !Objects.equals(request.getVersion(), supplier.version())) {
            throw new ObjectOptimisticLockingFailureException(Supplier.class, id);
        }

        String name = request.hasName() ? request.getName() : supplier.name();
        String country = request.hasCountry() ? request.getCountry() : supplier.country();
        String contactName = request.hasContactName() ? request.getContactName() : supplier.contactName();
        String contactDetails = request.hasContactDetails() ? request.getContactDetails() : supplier.contactDetails();
        String paymentNotes = request.hasPaymentNotes() ? request.getPaymentNotes() : supplier.paymentNotes();

        if (request.hasName() && repository.existsOtherLiveName(name.strip(), id)) {
            throw new IllegalStateException("A supplier named '" + name.strip() + "' already exists");
        }

        // The aggregate re-checks everything and tidies it (trim, upper-case, blank to null).
        supplier.change(name, country, contactName, contactDetails, paymentNotes);
        if (request.hasActive()) {
            if (request.getActive()) {
                supplier.activate();
            } else {
                supplier.deactivate();
            }
        }
        return repository.saveAndFlush(supplier);
    }

    /**
     * Each field that was sent is held to the rules the add form's request carries:
     * the same annotations, so there is one set. A name or country sent as null or
     * blank fails there ({@code @NotBlank}); an optional field sent as null or blank
     * passes, and that is how it is cleared.
     */
    private void rejectInvalidFields(UpdateSupplierRequest request) {
        List<FieldErrorView> problems = new ArrayList<>();
        check(problems, "name", request.hasName(), request.getName());
        check(problems, "country", request.hasCountry(), request.getCountry());
        check(problems, "contactName", request.hasContactName(), request.getContactName());
        check(problems, "contactDetails", request.hasContactDetails(), request.getContactDetails());
        check(problems, "paymentNotes", request.hasPaymentNotes(), request.getPaymentNotes());
        if (request.hasActive() && request.getActive() == null) {
            problems.add(new FieldErrorView("active", "must be true or false"));
        }
        if (request.hasVersion() && request.getVersion() == null) {
            problems.add(new FieldErrorView("version", "must be a whole number of 0 or more, or left out"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidFieldsException(problems);
        }
    }

    private void check(List<FieldErrorView> problems, String field, boolean sent, String value) {
        if (!sent) {
            return;
        }
        for (ConstraintViolation<CreateSupplierRequest> violation
                : validator.validateValue(CreateSupplierRequest.class, field, value)) {
            problems.add(new FieldErrorView(field, violation.getMessage()));
        }
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
