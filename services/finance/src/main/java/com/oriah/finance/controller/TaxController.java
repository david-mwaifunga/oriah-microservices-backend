package com.oriah.finance.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

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

import com.oriah.finance.dto.ReversalRequest;
import com.oriah.finance.dto.TaxCorrectionRequest;
import com.oriah.finance.dto.TaxEntryRequest;
import com.oriah.finance.dto.TaxEntryResponse;
import com.oriah.finance.dto.TaxTypeResponse;
import com.oriah.finance.service.IdempotencyGuard;
import com.oriah.finance.service.IdempotencyGuard.Guarded;
import com.oriah.finance.service.TaxService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Tax paid to ZRA, typed in. FINANCE_ADMIN only. Every write needs an {@code Idempotency-Key}. */
@ProblemResponses
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Validated
@Tag(name = "Taxes", description = "Turnover tax paid to ZRA, entered by hand. Append-only; shown below the profit line")
public class TaxController {

    private static final String BASE = "/api/v1/admin/finance/tax-entries";

    private final TaxService taxes;
    private final IdempotencyGuard idempotency;

    public TaxController(TaxService taxes, IdempotencyGuard idempotency) {
        this.taxes = taxes;
        this.idempotency = idempotency;
    }

    @WriteResponses
    @PostMapping("/tax-entries")
    @Transactional
    @Operation(operationId = "addTaxEntry", summary = "Enter a tax paid to ZRA (Idempotency-Key required)")
    public ResponseEntity<TaxEntryResponse> add(@RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody TaxEntryRequest request, Authentication who) {
        String actor = ExpenseController.actor(who);
        return answer(idempotency.run(actor, key, "POST " + BASE, request, TaxEntryResponse.class, () -> taxes.add(actor, request)));
    }

    @OkResponse
    @GetMapping("/tax-entries")
    @Operation(operationId = "listTaxEntries", summary = "Tax entries, newest first, optionally for one month")
    public List<TaxEntryResponse> list(@RequestParam(required = false) String month) {
        return taxes.list(month);
    }

    @NotFoundResponse
    @OkResponse
    @GetMapping("/tax-entries/{id}")
    @Operation(operationId = "getTaxEntry", summary = "One tax entry")
    public TaxEntryResponse get(@PathVariable UUID id) {
        return taxes.get(id);
    }

    @WriteResponses
    @NotFoundResponse
    @PostMapping("/tax-entries/{id}/reversal")
    @Transactional
    @Operation(operationId = "reverseTaxEntry", summary = "Take a tax entry back, with a reason (Idempotency-Key required)")
    public ResponseEntity<TaxEntryResponse> reverse(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody ReversalRequest request,
                                                    Authentication who) {
        String actor = ExpenseController.actor(who);
        return answer(idempotency.run(actor, key, "POST " + BASE + "/" + id + "/reversal", request, TaxEntryResponse.class, () -> taxes.reverse(actor, id, request.reason())));
    }

    @WriteResponses
    @NotFoundResponse
    @PostMapping("/tax-entries/{id}/correction")
    @Transactional
    @Operation(operationId = "correctTaxEntry", summary = "Correct a tax entry: a reversal plus a new entry in one step (Idempotency-Key required)")
    public ResponseEntity<TaxEntryResponse> correct(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    @Valid @RequestBody TaxCorrectionRequest request, Authentication who) {
        String actor = ExpenseController.actor(who);
        return answer(idempotency.run(actor, key, "POST " + BASE + "/" + id + "/correction", request, TaxEntryResponse.class, () -> taxes.correct(actor, id, request)));
    }

    @OkResponse
    @GetMapping("/tax-types")
    @Operation(operationId = "listTaxTypes", summary = "The taxes that can be entered here (turnover tax; customs charges are refused)")
    public List<TaxTypeResponse> types() {
        return taxes.types();
    }

    private static ResponseEntity<TaxEntryResponse> answer(Guarded<TaxEntryResponse> done) {
        if (done.replay()) {
            return ResponseEntity.ok(done.body());
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "/" + done.body().id())).body(done.body());
    }
}
