package com.oriah.finance.dto;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Something that did not add up. {@code id} is the occurrence that can be acknowledged. {@code status}: {@code OPEN}, {@code ACKNOWLEDGED} (someone looked, with a note) or
 * {@code RESOLVED} (the system closed it: the cause went away). {@code detail} holds ids, codes and amounts the system worked out, never a value a person typed.
 */
@Schema(name = "Anomaly")
public record AnomalyResponse(long id, String code, String subject, String severity, String status, Map<String, Object> detail, Instant raisedAt, String acknowledgedBy, Instant acknowledgedAt,
                              String note) {
}
