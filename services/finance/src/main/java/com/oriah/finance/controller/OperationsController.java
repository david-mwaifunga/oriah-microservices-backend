package com.oriah.finance.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.finance.dto.AckRequest;
import com.oriah.finance.dto.AnomalyResponse;
import com.oriah.finance.dto.ReverifyRequest;
import com.oriah.finance.dto.ReverifyResponse;
import com.oriah.finance.dto.StatusResponse;
import com.oriah.finance.service.IdempotencyGuard;
import com.oriah.finance.service.IdempotencyGuard.Guarded;
import com.oriah.finance.service.OperationsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Whether the books can be trusted: the status, the anomalies, acknowledging one, and reading a producer's history again. FINANCE_ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Validated
@Tag(name = "Operations", description = "Catch-up status, anomalies and re-verify")
public class OperationsController {

    private final OperationsService operations;
    private final IdempotencyGuard idempotency;

    public OperationsController(OperationsService operations, IdempotencyGuard idempotency) {
        this.operations = operations;
        this.idempotency = idempotency;
    }

    @GetMapping("/status")
    @Operation(operationId = "financeStatus", summary = "Where each producer's catch-up stands and how many errors and warnings are open")
    public StatusResponse status() {
        return operations.status();
    }

    @GetMapping("/anomalies")
    @Operation(operationId = "listAnomalies", summary = "What does not add up: the open ones (default) or all")
    public List<AnomalyResponse> anomalies(@RequestParam(required = false) String status) {
        return operations.anomalies(status);
    }

    @PostMapping("/anomalies/{id}/ack")
    @Transactional
    @Operation(operationId = "acknowledgeAnomaly", summary = "Say an error or warning has been looked at, with a note (Idempotency-Key required)")
    public ResponseEntity<AnomalyResponse> acknowledge(@PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody AckRequest request,
                                                       Authentication who) {
        String actor = ExpenseController.actor(who);
        String path = "/api/v1/admin/finance/anomalies/" + id + "/ack";
        Guarded<AnomalyResponse> done = idempotency.run(actor, key, "POST " + path, request, AnomalyResponse.class, () -> operations.acknowledge(actor, id, request.note()));
        return done.replay() ? ResponseEntity.ok(done.body()) : ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/admin/finance/anomalies?status=ALL")).body(done.body());
    }

    @PostMapping("/catch-up/reverify")
    @Transactional
    @Operation(operationId = "reverifyCatchUp", summary = "Read a producer's whole history again at the next start (Idempotency-Key required)")
    public ResponseEntity<ReverifyResponse> reverify(@RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody ReverifyRequest request, Authentication who) {
        String actor = ExpenseController.actor(who);
        Guarded<ReverifyResponse> done = idempotency.run(actor, key, "POST /api/v1/admin/finance/catch-up/reverify", request, ReverifyResponse.class, () -> operations.reverify(request.producer()));
        return done.replay() ? ResponseEntity.ok(done.body()) : ResponseEntity.status(HttpStatus.CREATED).body(done.body());
    }
}
