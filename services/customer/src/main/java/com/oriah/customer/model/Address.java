package com.oriah.customer.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.oriah.jpa.AuditableEntity;

/**
 * Postal address belonging to a {@link Customer}. The {@code isDefault}
 * flag marks the address pre-selected at checkout; the Customer aggregate
 * enforces "at most one default" when adding addresses.
 *
 * <p>Country is the ISO 3166-1 alpha-2 code (CHAR(2) at the DB level).
 */
@Entity
@Table(name = "addresses")
public class Address extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String line1;

    @Column
    private String line2;

    @Column(nullable = false)
    private String city;

    @Column
    private String state;

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    protected Address() {}

    Address(Customer customer, String label, String line1, String line2, String city,
            String state, String postalCode, String country, boolean isDefault) {
        this.id = UUID.randomUUID();
        this.customer = customer;
        this.label = label;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
        this.isDefault = isDefault;
    }

    public UUID id() { return id; }
    public String label() { return label; }
    public String line1() { return line1; }
    public String line2() { return line2; }
    public String city() { return city; }
    public String state() { return state; }
    public String postalCode() { return postalCode; }
    public String country() { return country; }
    public boolean isDefault() { return isDefault; }

    void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}
