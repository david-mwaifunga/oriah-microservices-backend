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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.finance.dto.StatementCheckRequest;
import com.oriah.finance.dto.StatementCheckResponse;
import com.oriah.finance.service.IdempotencyGuard;
import com.oriah.finance.service.IdempotencyGuard.Guarded;
import com.oriah.finance.service.StatementCheckService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** What each provider's statement says it charged for a month, for the reconciliation. FINANCE_ADMIN only. */
@ProblemResponses
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Validated
@Tag(name = "Reconciliation", description = "Provider statements typed in, to reconcile the month against what finance recorded")
public class StatementCheckController {

    private static final String BASE = "/api/v1/admin/finance/statement-checks";

    private final StatementCheckService checks;
    private final IdempotencyGuard idempotency;

    public StatementCheckController(StatementCheckService checks, IdempotencyGuard idempotency) {
        this.checks = checks;
        this.idempotency = idempotency;
    }

    @WriteResponses
    @PostMapping("/statement-checks")
    @Transactional
    @Operation(operationId = "addStatementCheck", summary = "Enter what a provider's statement says it charged in fees for a month (Idempotency-Key required)")
    public ResponseEntity<StatementCheckResponse> add(@RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody StatementCheckRequest request,
                                                      Authentication who) {
        String actor = ExpenseController.actor(who);
        Guarded<StatementCheckResponse> done = idempotency.run(actor, key, "POST " + BASE, request, StatementCheckResponse.class, () -> checks.add(actor, request));
        return done.replay() ? ResponseEntity.ok(done.body()) : ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "?month=" + request.month())).body(done.body());
    }

    @OkResponse
    @GetMapping("/statement-checks")
    @Operation(operationId = "listStatementChecks", summary = "Every statement entry for a month, oldest first")
    public List<StatementCheckResponse> list(@RequestParam String month) {
        return checks.list(month);
    }

    @OkResponse
    @GetMapping("/statement-checks/latest")
    @Operation(operationId = "latestStatementChecks", summary = "The latest statement entry for each provider for a month")
    public List<StatementCheckResponse> latest(@RequestParam String month) {
        return checks.latest(month);
    }
}
