package com.oriah.customer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.oriah.customer.model.Customer;

public record CustomerResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Instant createdAt,
        List<AddressResponse> addresses
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.id(),
                c.email(),
                c.firstName(),
                c.lastName(),
                c.createdAt(),
                c.addresses().stream().map(AddressResponse::from).toList()
        );
    }
}
