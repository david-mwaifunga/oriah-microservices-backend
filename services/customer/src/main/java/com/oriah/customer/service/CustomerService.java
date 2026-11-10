package com.oriah.customer.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.customer.dto.AddressRequest;
import com.oriah.customer.dto.CustomerResponse;
import com.oriah.customer.dto.CustomerUpdateRequest;
import com.oriah.customer.model.Address;
import com.oriah.customer.model.Customer;
import com.oriah.customer.repository.CustomerRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CustomerService {

    /** PostgreSQL's SQLSTATE for a unique violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** The name PostgreSQL gave the {@code UNIQUE} on {@code keycloak_id} in V1__create_customers.sql. */
    private static final String KEYCLOAK_ID_CONSTRAINT = "customers_keycloak_id_key";

    private final CustomerRepository repository;
    private final TransactionTemplate transactions;

    public CustomerService(CustomerRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A customer, and whether THIS call is the one that created the profile. */
    public record Provisioned(Customer customer, boolean created) {}

    /**
     * Idempotent self-provisioning: returns the existing customer for the given
     * Keycloak sub, or creates one if this is the first time we have seen them. The
     * first POST /me bootstraps the row from the caller's JWT claims; later POSTs
     * just return it.
     *
     * <p>Two first calls at the same moment (two tabs, a double click) both look,
     * both find nothing and both insert, and the database's UNIQUE on
     * {@code keycloak_id} refuses the second. That one must not fail: the profile
     * exists, which is what it wanted, so it returns the winner's profile and says
     * it created nothing ({@code created = false}). The caller turns that into 200
     * rather than 201.
     *
     * <p>Not itself {@code @Transactional}, on purpose. After a refused insert the
     * transaction is dead (PostgreSQL aborts it) and cannot be used to read the
     * winner's row, so the insert runs in a short transaction of its own and the
     * read afterwards in a fresh one.
     */
    public Provisioned provision(String keycloakId, String email, String firstName, String lastName) {
        Optional<Customer> existing = repository.findByKeycloakId(keycloakId);
        if (existing.isPresent()) {
            return new Provisioned(existing.get(), false);
        }
        try {
            Customer saved = transactions.execute(status ->
                    repository.saveAndFlush(new Customer(keycloakId, email, firstName, lastName)));
            log.info("Created customer id={} keycloakId={}", saved.id(), keycloakId);
            return new Provisioned(saved, true);
        } catch (DataIntegrityViolationException refused) {
            if (!isDuplicateKeycloakId(refused)) {
                throw refused;
            }
            log.info("Customer for keycloakId={} was created by a simultaneous call; returning it", keycloakId);
            return new Provisioned(repository.findByKeycloakId(keycloakId).orElseThrow(() -> refused), false);
        }
    }

    /** Both the error code and the constraint name must match, anywhere along the cause chain. */
    private static boolean isDuplicateKeycloakId(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation
                    && UNIQUE_VIOLATION.equals(violation.getSQLState())
                    && KEYCLOAK_ID_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findByKeycloakId(String keycloakId) {
        log.debug("findByKeycloakId keycloakId={}", keycloakId);
        return repository.findByKeycloakId(keycloakId);
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findById(UUID id) {
        log.debug("findById id={}", id);
        return repository.findById(id);
    }

    /**
     * The admin list, mapped to DTOs <strong>inside</strong> the transaction.
     *
     * <p>This used to return {@code Page<Customer>} and let the controller
     * call {@code CustomerResponse::from}. That mapping iterates the lazy
     * {@code addresses} collection, by which point the transaction had closed,
     * so every call died with
     *
     * <pre>LazyInitializationException: Cannot lazily initialize collection
     * of role Customer.addresses</pre>
     *
     * and the endpoint answered 500 for its entire life. No test caught it
     * because none listed a customer that HAD an address; it was found by
     * calling the live endpoint while building the admin UI on top of it.
     *
     * <p>Mapping here rather than widening the fetch keeps pagination on the
     * database. {@code @BatchSize} on the collection keeps it to two queries.
     */
    @Transactional(readOnly = true)
    public Page<CustomerResponse> findAllForAdmin(Pageable pageable) {
        log.debug("findAll page={} size={}", pageable.getPageNumber(), pageable.getPageSize());
        return repository.findAll(pageable).map(CustomerResponse::from);
    }

    @Transactional
    public Optional<Customer> updateProfile(String keycloakId, CustomerUpdateRequest req) {
        return repository.findByKeycloakId(keycloakId).map(c -> {
            c.updateProfile(req.email(), req.firstName(), req.lastName());
            log.info("Updated profile id={}", c.id());
            return c;
        });
    }

    @Transactional
    public Optional<Address> addAddress(String keycloakId, AddressRequest req) {
        return repository.findByKeycloakId(keycloakId).map(c -> {
            Address a = c.addAddress(req.label(), req.line1(), req.line2(), req.city(),
                    req.state(), req.postalCode(), req.country(), req.isDefault());
            log.info("Added address id={} customerId={} isDefault={}", a.id(), c.id(), a.isDefault());
            return a;
        });
    }

    @Transactional
    public boolean removeAddress(String keycloakId, UUID addressId) {
        Optional<Customer> customer = repository.findByKeycloakId(keycloakId);
        if (customer.isEmpty()) return false;
        boolean removed = customer.get().removeAddress(addressId);
        if (removed) {
            log.info("Removed address id={} customerId={}", addressId, customer.get().id());
        }
        return removed;
    }

    @Transactional(readOnly = true)
    public List<Address> listAddresses(String keycloakId) {
        return repository.findByKeycloakId(keycloakId)
                .map(Customer::addresses)
                .orElse(List.of());
    }
}
