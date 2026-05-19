package com.oriah.customer.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * One row per Keycloak user. Identified by {@link #keycloakId} (the
 * {@code sub} claim of the JWT). Auto-provisioned the first time the
 * customer hits {@code POST /api/v1/customers/me}.
 *
 * <p>The internal {@code id} (UUID) is the FK target for other services
 * (cart, order). We don't expose Keycloak's id outside customer-service
 * — services that need to refer to a customer use this UUID.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true)
    private String keycloakId;

    @Column(nullable = false)
    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    protected Customer() {}

    public Customer(String keycloakId, String email, String firstName, String lastName) {
        this.id = UUID.randomUUID();
        this.keycloakId = requireNonBlank(keycloakId, "keycloakId");
        this.email = requireNonBlank(email, "email");
        this.firstName = requireNonBlank(firstName, "firstName");
        this.lastName = requireNonBlank(lastName, "lastName");
        this.createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public String keycloakId() { return keycloakId; }
    public String email() { return email; }
    public String firstName() { return firstName; }
    public String lastName() { return lastName; }
    public Instant createdAt() { return createdAt; }
    public List<Address> addresses() { return Collections.unmodifiableList(addresses); }

    public void updateProfile(String email, String firstName, String lastName) {
        if (email != null && !email.isBlank()) this.email = email;
        if (firstName != null && !firstName.isBlank()) this.firstName = firstName;
        if (lastName != null && !lastName.isBlank()) this.lastName = lastName;
    }

    /**
     * Add a new address. The new address becomes default if the customer has none yet
     * (saves a separate "first address auto-default" rule in the controller).
     */
    public Address addAddress(String label, String line1, String line2, String city,
                              String state, String postalCode, String country, boolean isDefault) {
        boolean makeDefault = isDefault || addresses.isEmpty();
        if (makeDefault) {
            // Ensure single-default invariant.
            addresses.forEach(a -> a.setDefault(false));
        }
        Address address = new Address(this, label, line1, line2, city, state, postalCode, country, makeDefault);
        addresses.add(address);
        return address;
    }

    public boolean removeAddress(UUID addressId) {
        return addresses.removeIf(a -> a.id().equals(addressId));
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
