package com.oriah.finance.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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

import com.oriah.finance.dto.CorrectionRequest;
import com.oriah.finance.dto.ExpenseCategoryResponse;
import com.oriah.finance.dto.ExpensePage;
import com.oriah.finance.dto.ExpenseRequest;
import com.oriah.finance.dto.ExpenseResponse;
import com.oriah.finance.dto.ReversalRequest;
import com.oriah.finance.service.ExpenseService;
import com.oriah.finance.service.IdempotencyGuard;
import com.oriah.finance.service.IdempotencyGuard.Guarded;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Expenses: typed in, reversed, corrected and read. FINANCE_ADMIN only (the owner and the bookkeeper). Every write needs an {@code Idempotency-Key}: sent again with the same
 * request it answers 200 with the original answer and does nothing; a new write answers 201.
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Validated
@Tag(name = "Expenses", description = "Expenses typed in by hand, and the corrections of any expense. Append-only: there is no update and no delete")
public class ExpenseController {

    private static final String BASE = "/api/v1/admin/finance/expenses";

    private final ExpenseService expenses;
    private final IdempotencyGuard idempotency;

    public ExpenseController(ExpenseService expenses, IdempotencyGuard idempotency) {
        this.expenses = expenses;
        this.idempotency = idempotency;
    }

    @PostMapping("/expenses")
    @Transactional
    @Operation(operationId = "addExpense", summary = "Type in an expense (Idempotency-Key required)")
    public ResponseEntity<ExpenseResponse> add(@RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody ExpenseRequest request, Authentication who) {
        String actor = actor(who);
        Guarded<ExpenseResponse> done = idempotency.run(actor, key, "POST " + BASE, request, ExpenseResponse.class, () -> expenses.add(actor, request));
        return answer(done);
    }

    @GetMapping("/expenses")
    @Operation(operationId = "listExpenses", summary = "Expenses of a month, newest first: typed in and posted, each saying where it came from")
    public ExpensePage list(@RequestParam(required = false) String month, @RequestParam(required = false) String category, @RequestParam(required = false) String source,
                            @RequestParam(defaultValue = "false") boolean includeReversed, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return expenses.list(month, category, source, includeReversed, page, size);
    }

    @GetMapping("/expenses/{id}")
    @Operation(operationId = "getExpense", summary = "One expense")
    public ExpenseResponse get(@PathVariable UUID id) {
        return expenses.get(id);
    }

    @PostMapping("/expenses/{id}/reversal")
    @Transactional
    @Operation(operationId = "reverseExpense", summary = "Take an expense back, with a reason (Idempotency-Key required)")
    public ResponseEntity<ExpenseResponse> reverse(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody ReversalRequest request,
                                                   Authentication who) {
        String actor = actor(who);
        return answer(idempotency.run(actor, key, "POST " + BASE + "/" + id + "/reversal", request, ExpenseResponse.class, () -> expenses.reverse(actor, id, request.reason())));
    }

    @PostMapping("/expenses/{id}/correction")
    @Transactional
    @Operation(operationId = "correctExpense", summary = "Correct or re-classify an expense: a reversal plus a new expense in one step (Idempotency-Key required)")
    public ResponseEntity<ExpenseResponse> correct(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   @Valid @RequestBody CorrectionRequest request, Authentication who) {
        String actor = actor(who);
        return answer(idempotency.run(actor, key, "POST " + BASE + "/" + id + "/correction", request, ExpenseResponse.class, () -> expenses.correct(actor, id, request)));
    }

    @GetMapping("/expense-categories")
    @Operation(operationId = "listExpenseCategories", summary = "The headings an expense is entered under, and what each needs")
    public List<ExpenseCategoryResponse> categories() {
        return expenses.categories();
    }

    private static ResponseEntity<ExpenseResponse> answer(Guarded<ExpenseResponse> done) {
        if (done.replay()) {
            return ResponseEntity.ok(done.body());
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(BASE + "/" + done.body().id())).body(done.body());
    }

    static String actor(Authentication who) {
        return "user:" + ((Jwt) who.getPrincipal()).getSubject();
    }
}
