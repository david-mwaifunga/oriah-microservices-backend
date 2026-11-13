package com.oriah.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What staff send to reject a claim as false: the proof. {@code NOT_FOUND_AT_PROVIDER} says nothing was found at the provider or bank and needs the
 * {@code evidence} (what was looked at, at least ten characters); {@code REFERENCE_ALREADY_USED} needs none, because the service checks it itself
 * against the confirmations and held money. The order, method and reference are the claim's own and are never sent.
 */
public record RejectClaimRequest(
        @NotBlank(message = "proofType is required (NOT_FOUND_AT_PROVIDER or REFERENCE_ALREADY_USED)") String proofType,
        @Size(max = 500, message = "evidence is at most 500 characters") String evidence) {
}
