package com.oriah.finance.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.finance.dto.ProductReportResponse;
import com.oriah.finance.dto.ProfitReportResponse;
import com.oriah.finance.service.ProfitReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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

    public ReportController(ProfitReportService reports) {
        this.reports = reports;
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
}
