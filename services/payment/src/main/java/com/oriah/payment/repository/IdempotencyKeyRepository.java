package com.oriah.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.payment.model.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
}
