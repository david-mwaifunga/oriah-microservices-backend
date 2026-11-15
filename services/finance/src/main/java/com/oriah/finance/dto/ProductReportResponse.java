package com.oriah.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The month's gross profit split by product: each product's net revenue after discounts less its goods and packaging cost, so the products add up exactly to the shop's gross
 * profit. Stock written off or given away is a column of its own (never inside the gross profit); delivery and period expenses are never spread across products. One closing
 * row, {@code notTiedToProduct}, holds anything no product can be named for. {@code checksum} compares the table's total with the report's gross profit.
 */
@Schema(name = "ProductReport")
public record ProductReportResponse(String month, Instant asOf, List<Row> items, int page, int size, long total, Row totals, Row notTiedToProduct, Checksum checksum) {

    @Schema(name = "ProductReportRow")
    public record Row(UUID productId, String title, BigDecimal netRevenue, BigDecimal costOfGoods, BigDecimal costOfPackaging, BigDecimal grossProfit, BigDecimal writtenOff) {
    }

    @Schema(name = "ProductReportChecksum")
    public record Checksum(boolean ok, BigDecimal difference) {
    }
}
