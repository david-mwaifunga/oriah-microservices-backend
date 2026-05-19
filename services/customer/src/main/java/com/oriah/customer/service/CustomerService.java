package com.oriah.customer.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.customer.dto.AddressRequest;
import com.oriah.customer.dto.CustomerUpdateRequest;
import com.oriah.customer.model.Address;
import com.oriah.customer.model.Customer;
import com.oriah.customer.repository.CustomerRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /**
     * Idempotent self-provisioning: returns the existing customer for
     * the given Keycloak sub, or creates one if this is the first time
     * we've seen them. The first POST /me bootstraps the row from the
     * caller's JWT claims; subsequent POSTs just return it.
     */
    @Transactional
    public Customer getOrCreate(String keycloakId, String email, String firstName, String lastName) {
        return repository.findByKeycloakId(keycloakId)
                .orElseGet(() -> {
                    Customer created = new Customer(keycloakId, email, firstName, lastName);
                    Customer saved = repository.save(created);
                    log.info("Created customer id={} keycloakId={}", saved.id(), keycloakId);
                    return saved;
                });
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

    @Transactional(readOnly = true)
    public Page<Customer> findAll(Pageable pageable) {
        log.debug("findAll page={} size={}", pageable.getPageNumber(), pageable.getPageSize());
        return repository.findAll(pageable);
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
