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

import com.oriah.finance.dto.EntryPage;
import com.oriah.finance.dto.IssueRequest;
import com.oriah.finance.dto.IssueResponse;
import com.oriah.finance.dto.IssueSummary;
import com.oriah.finance.dto.ProductReportResponse;
import com.oriah.finance.dto.ProfitReportResponse;
import com.oriah.finance.service.IdempotencyGuard;
import com.oriah.finance.service.IdempotencyGuard.Guarded;
import com.oriah.finance.service.ProfitReportService;
import com.oriah.finance.service.ReportIssueService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The profit report. FINANCE_ADMIN only (the owner and the bookkeeper). {@code asOf} reproduces a month as finance knew it at an earlier moment; leaving it out is now.
 */
@RestController
@RequestMapping("/api/v1/admin/finance/reports")
@PreAuthorize("hasRole('FINANCE_ADMIN')")
@Validated
@Tag(name = "Reports", description = "The monthly profit report and gross profit by product")
public class ReportController {

    private final ProfitReportService reports;
    private final ReportIssueService issues;
    private final IdempotencyGuard idempotency;

    public ReportController(ProfitReportService reports, ReportIssueService issues, IdempotencyGuard idempotency) {
        this.reports = reports;
        this.issues = issues;
        this.idempotency = idempotency;
    }

    @GetMapping("/profit")
    @Operation(operationId = "profitReportRange", summary = "Profit reports for a range of months (at most 24)")
    public List<ProfitReportResponse> range(@RequestParam String from, @RequestParam String to, @RequestParam(required = false) String asOf) {
        return reports.range(from, to, asOf);
    }

    @GetMapping("/profit/{month}")
    @Operation(operationId = "profitReport", summary = "One month's profit report, as finance knew it at asOf (default now)")
    public ProfitReportResponse month(@PathVariable String month, @RequestParam(required = false) String asOf) {
        return reports.month(month, asOf);
    }

    @GetMapping("/profit/{month}/products")
    @Operation(operationId = "profitReportByProduct", summary = "Gross profit by product for a month: the products add up exactly to the shop's gross profit")
    public ProductReportResponse products(@PathVariable String month, @RequestParam(required = false) String asOf, @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return reports.products(month, asOf, page, size);
    }

    @GetMapping("/profit/{month}/entries")
    @Operation(operationId = "profitReportEntries", summary = "The ledger rows behind a month's figures, by report line, product, heading or order; changedSinceIssue lists what changed after the month was reported")
    public EntryPage entries(@PathVariable String month, @RequestParam(required = false) String line, @RequestParam(required = false) String product, @RequestParam(required = false) String category,
                             @RequestParam(required = false) String orderId, @RequestParam(defaultValue = "false") boolean changedSinceIssue, @RequestParam(required = false) String asOf,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return reports.entries(month, line, product, category, orderId, changedSinceIssue, asOf, page, size);
    }

    @PostMapping("/profit/{month}/issue")
    @Transactional
    @Operation(operationId = "issueProfitReport", summary = "Report a month: store it whole as it is now (Idempotency-Key required)")
    public ResponseEntity<IssueResponse> issue(@PathVariable String month, @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody IssueRequest request,
                                               Authentication who) {
        String actor = ExpenseController.actor(who);
        Guarded<IssueResponse> done = idempotency.run(actor, key, "POST /api/v1/admin/finance/reports/profit/" + month + "/issue", request, IssueResponse.class, () -> issues.issue(actor, month, request));
        if (done.replay()) {
            return ResponseEntity.ok(done.body());
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/admin/finance/reports/profit/" + month + "/issues")).body(done.body());
    }

    @GetMapping("/profit/{month}/issues")
    @Operation(operationId = "listProfitReportIssues", summary = "Every time a month was reported")
    public List<IssueSummary> issues(@PathVariable String month) {
        return issues.issues(month);
    }

    @GetMapping("/estimated-stock")
    @Operation(operationId = "estimatedStock", summary = "The standing list of consignment lines whose cost is an estimate (day-one stock)")
    public List<ProfitReportResponse.EstimatedLine> estimatedStock() {
        return reports.estimatedStock();
    }
}
