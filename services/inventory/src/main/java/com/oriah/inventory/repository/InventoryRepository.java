package com.oriah.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.inventory.model.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {
    // No custom queries yet — JpaRepository's findById, save, deleteById
    // cover every operation the service layer needs.
}
