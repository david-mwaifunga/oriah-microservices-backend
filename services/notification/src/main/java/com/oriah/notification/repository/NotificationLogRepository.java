package com.oriah.notification.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.notification.model.NotificationLog;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    Page<NotificationLog> findByKeycloakIdOrderByDeliveredAtDesc(String keycloakId, Pageable pageable);
}
