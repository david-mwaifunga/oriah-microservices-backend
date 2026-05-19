package com.oriah.customer.dto;

import java.util.UUID;

import com.oriah.customer.model.Address;

public record AddressResponse(
        UUID id,
        String label,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        boolean isDefault
) {
    public static AddressResponse from(Address a) {
        return new AddressResponse(
                a.id(),
                a.label(),
                a.line1(),
                a.line2(),
                a.city(),
                a.state(),
                a.postalCode(),
                a.country(),
                a.isDefault()
        );
    }
}
