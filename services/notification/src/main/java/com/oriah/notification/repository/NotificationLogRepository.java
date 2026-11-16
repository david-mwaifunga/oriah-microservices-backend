package com.oriah.notification.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.oriah.notification.model.NotificationLog;
import com.oriah.notification.model.RecipientKind;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    /**
     * One customer's own messages of every status, newest first. A message to staff or words for a caller has no owner and is never among them;
     * the recipient kind is asked for as well, so that stays true whatever a row's owner column holds.
     */
    Page<NotificationLog> findByKeycloakIdAndRecipientKindOrderByRecordedAtDesc(String keycloakId, RecipientKind recipientKind, Pageable pageable);
}
