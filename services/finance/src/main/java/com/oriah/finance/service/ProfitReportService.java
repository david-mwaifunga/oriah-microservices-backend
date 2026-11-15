package com.oriah.finance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.config.BusinessCalendar;
import com.oriah.finance.dto.ProductReportResponse;
import com.oriah.finance.dto.ProfitReportResponse;
import com.oriah.finance.dto.ProfitReportResponse.ExpenseLine;
import com.oriah.finance.dto.ProfitReportResponse.Line;
import com.oriah.finance.dto.ProfitReportResponse.Memo;
import com.oriah.finance.dto.ProfitReportResponse.StatementLine;
import com.oriah.finance.dto.ProfitReportResponse.Status;
import com.oriah.finance.dto.ProfitReportResponse.WrittenOff;
import com.oriah.finance.exception.FinanceProblem;

/**
 * The monthly profit report, worked out from the ledger and nothing else: every figure is the sum of the rows with that month's effect, as finance knew them at {@code asOf} (a
 * row counts if finance recorded it by then). Months are Lusaka months; an old report is reproduced by asking for its {@code asOf}. A reversing entry is just another row, so it
 * nets out without any special case. Nothing here rounds: sums of ngwee are exact.
 *
 * <p>Gross profit is on GOODS: net revenue after discounts less the cost of goods and of packaging. The delivery charge is revenue (the client's decision) and sits on its own line
 * below gross profit, so the table by product adds up exactly to gross profit with no delivery row.
 */
@Service
@Transactional(readOnly = true)
public class ProfitReportService {

    static final int MAX_MONTHS = 24;
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public ProfitReportService(JdbcTemplate jdbc, BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the month

    public ProfitReportResponse month(String month, String asOfText) {
        YearMonth ym = parse(month);
        return month(ym, asOf(asOfText));
    }

    public List<ProfitReportResponse> range(String from, String to, String asOfText) {
        YearMonth a = parse(from);
        YearMonth b = parse(to);
        if (b.isBefore(a)) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "The range ends before it begins");
        }
        if (a.until(b, java.time.temporal.ChronoUnit.MONTHS) + 1 > MAX_MONTHS) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "month-range-too-long", "A report covers at most " + MAX_MONTHS + " months at a time");
        }
        Instant asOf = asOf(asOfText);
        List<ProfitReportResponse> months = new ArrayList<>();
        for (YearMonth m = a; !m.isAfter(b); m = m.plusMonths(1)) {
            months.add(month(m, asOf));
        }
        return months;
    }

    ProfitReportResponse month(YearMonth ym, Instant asOf) {
        LocalDate first = ym.atDay(1);
        Timestamp cut = Timestamp.from(asOf);
        Map<String, BigDecimal> sums = new HashMap<>();
        jdbc.query("SELECT account, sum(amount) FROM ledger_entry WHERE effect_month = ? AND recorded_at <= ? GROUP BY account",
                rs -> { sums.put(rs.getString(1), rs.getBigDecimal(2)); }, first, cut);
        BigDecimal goods = sum(sums, "REVENUE_GOODS");
        BigDecimal discounts = sum(sums, "DISCOUNTS");
        BigDecimal afterDiscounts = goods.subtract(discounts);
        BigDecimal cogsGoods = sum(sums, "COGS_GOODS");
        BigDecimal cogsPackaging = sum(sums, "COGS_PACKAGING");
        BigDecimal cogs = cogsGoods.add(cogsPackaging);
        BigDecimal gross = afterDiscounts.subtract(cogs);
        BigDecimal delivery = sum(sums, "REVENUE_DELIVERY");
        BigDecimal stockOut = sum(sums, "STOCK_OUT");
        BigDecimal expenses = sum(sums, "EXPENSE");
        BigDecimal beforeTax = gross.add(delivery).subtract(stockOut).subtract(expenses);
        BigDecimal tax = sum(sums, "TAX_ENTERED");
        BigDecimal afterTax = beforeTax.subtract(tax);

        List<Line> lines = List.of(
                new Line(1, "goodsAtListPrice", "Goods sold, at list price", goods),
                new Line(2, "discountsGiven", "Less: discounts given", discounts),
                new Line(3, "goodsAfterDiscounts", "Revenue from goods, after discounts", afterDiscounts),
                new Line(4, "costOfGoodsGoods", "Cost of goods sold: goods", cogsGoods),
                new Line(5, "costOfGoodsPackaging", "Cost of goods sold: packaging", cogsPackaging),
                new Line(6, "costOfGoodsSold", "Cost of goods sold", cogs),
                new Line(7, "grossProfit", "Gross profit", gross),
                new Line(8, "deliveryCharges", "Delivery charges collected", delivery),
                new Line(9, "stockWrittenOff", "Stock written off or given away, at cost", stockOut),
                new Line(10, "expenses", "Expenses", expenses),
                new Line(11, "profitBeforeTurnoverTax", "Profit before turnover tax", beforeTax),
                new Line(12, "turnoverTaxEntered", "Turnover tax entered for the month", tax),
                new Line(13, "profitAfterTurnoverTax", "Profit after turnover tax", afterTax));

        List<WrittenOff> writtenOff = jdbc.query("SELECT coalesce(reason, 'UNKNOWN'), sum(amount) FROM ledger_entry WHERE account = 'STOCK_OUT' AND effect_month = ? AND recorded_at <= ? "
                + "GROUP BY reason ORDER BY reason", (rs, n) -> new WrittenOff(rs.getString(1), rs.getBigDecimal(2)), first, cut);

        return new ProfitReportResponse(ym.toString(), asOf, "ZMW", lines, writtenOff, expenseLines(first, cut), memo(ym, first, cut, expenses), disclosure(first, cut, cogs.add(stockOut)),
                status(first, cut));
    }

    private List<ExpenseLine> expenseLines(LocalDate first, Timestamp cut) {
        Map<String, BigDecimal[]> byCategory = new TreeMap<>();
        Map<String, String> labels = new HashMap<>();
        jdbc.query("""
                SELECT l.category, c.label, coalesce(x.origin, 'POSTED'), sum(l.amount)
                  FROM ledger_entry l JOIN expense_category c ON c.code = l.category LEFT JOIN expense x ON x.ledger_entry_id = l.entry_id
                 WHERE l.account = 'EXPENSE' AND l.effect_month = ? AND l.recorded_at <= ? GROUP BY l.category, c.label, coalesce(x.origin, 'POSTED')""", rs -> {
            BigDecimal[] pair = byCategory.computeIfAbsent(rs.getString(1), k -> new BigDecimal[] {ZERO, ZERO});
            labels.put(rs.getString(1), rs.getString(2));
            if ("KEYED".equals(rs.getString(3))) {
                pair[1] = pair[1].add(rs.getBigDecimal(4));
            } else {
                pair[0] = pair[0].add(rs.getBigDecimal(4));
            }
        }, first, cut);
        List<ExpenseLine> out = new ArrayList<>();
        byCategory.forEach((code, pair) -> out.add(new ExpenseLine(code, labels.get(code), pair[0], pair[1], pair[0].add(pair[1]))));
        return out;
    }

    private Memo memo(YearMonth ym, LocalDate first, Timestamp cut, BigDecimal expenses) {
        BigDecimal waived = nz(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entry WHERE account = 'MEMO_DELIVERY_WAIVED' AND effect_month = ? AND recorded_at <= ?", BigDecimal.class, first, cut));
        LocalDate next = first.plusMonths(1);
        BigDecimal held = nz(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entry WHERE account = 'MEMO_HELD_MONEY' AND effect_day < ? AND recorded_at <= ?", BigDecimal.class, next, cut));
        Instant start = calendar.startOf(ym);
        Instant end = calendar.endOf(ym);
        Integer late = jdbc.queryForObject("SELECT count(*) FROM sale WHERE channel = 'PHONE' AND sale_occurred_at IS NOT NULL AND arrived_at >= ? AND arrived_at < ? AND created_at <= ? "
                + "AND (sale_occurred_at < ? OR sale_occurred_at >= ?)", Integer.class, Timestamp.from(start), Timestamp.from(end), cut, Timestamp.from(start), Timestamp.from(end));
        return new Memo(waived, held, late == null ? 0 : late, agreedDeliveries(start, end, cut), statementLines(first, cut), overhead(start, end, cut, expenses));
    }

    private ProfitReportResponse.AgreedDeliveries agreedDeliveries(Instant start, Instant end, Timestamp cut) {
        return jdbc.queryForObject("""
                SELECT count(*), coalesce(sum(agreed_amount), 0), coalesce(sum(quoted_amount), 0), coalesce(sum(quoted_amount - agreed_amount) FILTER (WHERE quoted_amount IS NOT NULL), 0),
                       count(*) FILTER (WHERE quoted_amount IS NULL)
                  FROM sale WHERE delivery_price_source = 'AGREED' AND arrived_at >= ? AND arrived_at < ? AND created_at <= ?""",
                (rs, n) -> new ProfitReportResponse.AgreedDeliveries(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getInt(5)),
                Timestamp.from(start), Timestamp.from(end), cut);
    }

    private ProfitReportResponse.Overhead overhead(Instant start, Instant end, Timestamp cut, BigDecimal expenses) {
        Long pieces = jdbc.queryForObject("SELECT coalesce(sum(l.quantity), 0) FROM sale_line l JOIN sale s ON s.confirmation_id = l.confirmation_id WHERE s.arrived_at >= ? AND s.arrived_at < ? AND s.created_at <= ?",
                Long.class, Timestamp.from(start), Timestamp.from(end), cut);
        long n = pieces == null ? 0 : pieces;
        return new ProfitReportResponse.Overhead(expenses, n, n == 0 ? null : expenses.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ the estimated opening stock

    private ProfitReportResponse.Disclosure disclosure(LocalDate first, Timestamp cut, BigDecimal costThisMonth) {
        BigDecimal estimated = nz(jdbc.queryForObject("SELECT sum(amount) FROM ledger_entry WHERE effect_month = ? AND recorded_at <= ? AND estimated AND account IN ('COGS_GOODS', 'COGS_PACKAGING', 'STOCK_OUT')",
                BigDecimal.class, first, cut));
        Integer saleLines = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT sale_line_id FROM ledger_entry WHERE effect_month = ? AND recorded_at <= ? AND estimated AND account IN ('COGS_GOODS', 'COGS_PACKAGING')
                   AND sale_line_id IS NOT NULL GROUP BY sale_line_id HAVING sum(amount) <> 0) t""", Integer.class, first, cut);
        BigDecimal held = nz(jdbc.queryForObject("""
                SELECT sum(f.landed_total - coalesce(c.cost, 0))
                  FROM (SELECT DISTINCT ON (consignment_line_id) consignment_line_id, landed_total, is_estimated FROM consignment_line_fact ORDER BY consignment_line_id, revision DESC) f
                  LEFT JOIN (SELECT consignment_line_id, sum(amount) AS cost FROM ledger_entry WHERE source = 'STOCK_COST' AND recorded_at <= ? GROUP BY consignment_line_id) c
                    ON c.consignment_line_id = f.consignment_line_id WHERE f.is_estimated""", BigDecimal.class, cut));
        BigDecimal share = costThisMonth.signum() == 0 ? BigDecimal.ZERO.setScale(4) : estimated.divide(costThisMonth, 4, RoundingMode.HALF_UP);
        boolean approximate = estimated.signum() != 0 || held.signum() > 0;
        String banner = "";
        if (estimated.signum() != 0) {
            banner = "K" + money(estimated) + " of the K" + money(costThisMonth) + " of cost this month comes from estimated opening-stock costs, so margins this month are approximate.";
        } else if (held.signum() > 0) {
            banner = "Some opening stock is still on the shelf at an estimated cost: when it sells, that month will read as approximate.";
        }
        return new ProfitReportResponse.Disclosure(approximate, estimated, costThisMonth, share, saleLines == null ? 0 : saleLines, held, banner, estimatedStock());
    }

    /** The standing list: every consignment line whose latest announced revision is an estimate (day-one stock costed from the old book). */
    public List<ProfitReportResponse.EstimatedLine> estimatedStock() {
        return jdbc.query("""
                SELECT consignment_line_id, consignment_reference, sku, quantity, landed_total FROM (
                   SELECT DISTINCT ON (consignment_line_id) * FROM consignment_line_fact ORDER BY consignment_line_id, revision DESC) f
                 WHERE is_estimated ORDER BY received_at, consignment_line_id""",
                (rs, n) -> new ProfitReportResponse.EstimatedLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5)));
    }

    private static String money(BigDecimal amount) {
        return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(amount);
    }

    private List<StatementLine> statementLines(LocalDate first, Timestamp cut) {
        Map<String, BigDecimal> statements = new TreeMap<>();
        jdbc.query("SELECT DISTINCT ON (method) method, statement_fees FROM statement_check WHERE month = ? AND entered_at <= ? ORDER BY method, id DESC",
                rs -> { statements.put(rs.getString(1), rs.getBigDecimal(2)); }, first, cut);
        Map<String, BigDecimal> recorded = new TreeMap<>();
        jdbc.query("""
                SELECT cf.method, sum(l.amount) FROM ledger_entry l JOIN confirmation_fact cf ON cf.confirmation_id = l.confirmation_id AND cf.kind = 'CONFIRMED'
                 WHERE l.account = 'EXPENSE' AND l.category IN ('PAYMENT_CHARGES', 'PAYMENT_CHARGES_ADJUSTMENT') AND l.effect_month = ? AND l.recorded_at <= ? GROUP BY cf.method""",
                rs -> { recorded.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, first, cut);
        jdbc.query("""
                SELECT x.provider, sum(l.amount) FROM ledger_entry l JOIN expense x ON x.ledger_entry_id = l.entry_id
                 WHERE l.account = 'EXPENSE' AND l.category = 'BANK_MOBILE_ACCOUNT_FEES' AND l.effect_month = ? AND l.recorded_at <= ? AND x.provider IS NOT NULL GROUP BY x.provider""",
                rs -> { recorded.merge(rs.getString(1), rs.getBigDecimal(2), BigDecimal::add); }, first, cut);
        Map<String, Boolean> methods = new TreeMap<>();
        statements.keySet().forEach(m -> methods.put(m, true));
        recorded.keySet().forEach(m -> methods.put(m, true));
        List<StatementLine> out = new ArrayList<>();
        for (String method : methods.keySet()) {
            BigDecimal said = statements.get(method);
            BigDecimal ours = recorded.getOrDefault(method, ZERO);
            out.add(new StatementLine(method, said, ours, said == null ? null : said.subtract(ours)));
        }
        return out;
    }

    private Status status(LocalDate first, Timestamp cut) {
        int errors = openAnomalies("ERROR", cut);
        int warnings = openAnomalies("WARN", cut);
        Boolean complete = jdbc.queryForObject("SELECT count(*) > 0 AND bool_and(completed_at IS NOT NULL AND completed_at <= ?) FROM outbox_catch_up", Boolean.class, cut);
        BigDecimal notTied = nz(jdbc.queryForObject("""
                SELECT sum(abs(net)) FROM (SELECT account, sum(amount) AS net FROM ledger_entry WHERE product_id IS NULL AND effect_month = ? AND recorded_at <= ?
                   AND account IN ('REVENUE_GOODS', 'DISCOUNTS', 'COGS_GOODS', 'COGS_PACKAGING', 'STOCK_OUT') GROUP BY account) t""", BigDecimal.class, first, cut));
        boolean catchUp = Boolean.TRUE.equals(complete);
        List<Object[]> last = jdbc.query("SELECT issue_no, as_of, issued_at FROM report_issue WHERE month = ? AND issued_at <= ? ORDER BY issue_no DESC LIMIT 1",
                (rs, n) -> new Object[] {rs.getInt(1), rs.getTimestamp(2), rs.getTimestamp(3)}, first, cut);
        boolean issued = !last.isEmpty();
        long changed = 0;
        BigDecimal effect = ZERO;
        if (issued) {
            Timestamp issuedAsOf = (Timestamp) last.get(0)[1];
            changed = jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE effect_month = ? AND recorded_at > ? AND recorded_at <= ?", Long.class, first, issuedAsOf, cut);
            effect = nz(jdbc.queryForObject("""
                    SELECT sum(l.amount * a.profit_sign) FROM ledger_entry l JOIN ledger_account a ON a.code = l.account
                     WHERE l.effect_month = ? AND l.recorded_at > ? AND l.recorded_at <= ? AND a.profit_sign <> 0 AND l.account <> 'TAX_ENTERED'""", BigDecimal.class, first, issuedAsOf, cut));
        }
        return new Status(issued, issued ? (Integer) last.get(0)[0] : null, issued ? ((Timestamp) last.get(0)[2]).toInstant() : null, changed > 0, changed, effect, errors, warnings, catchUp, notTied,
                errors > 0 || !catchUp || notTied.signum() != 0);
    }

    // ------------------------------------------------------------------ the rows behind a figure

    private static final Map<String, List<String>> LINES = Map.ofEntries(
            Map.entry("goodsAtListPrice", List.of("REVENUE_GOODS")), Map.entry("discountsGiven", List.of("DISCOUNTS")),
            Map.entry("goodsAfterDiscounts", List.of("REVENUE_GOODS", "DISCOUNTS")), Map.entry("costOfGoodsGoods", List.of("COGS_GOODS")),
            Map.entry("costOfGoodsPackaging", List.of("COGS_PACKAGING")), Map.entry("costOfGoodsSold", List.of("COGS_GOODS", "COGS_PACKAGING")),
            Map.entry("grossProfit", List.of("REVENUE_GOODS", "DISCOUNTS", "COGS_GOODS", "COGS_PACKAGING")), Map.entry("deliveryCharges", List.of("REVENUE_DELIVERY")),
            Map.entry("stockWrittenOff", List.of("STOCK_OUT")), Map.entry("expenses", List.of("EXPENSE")), Map.entry("turnoverTaxEntered", List.of("TAX_ENTERED")));

    /** The ledger rows behind the month's figures, newest first; {@code line} is one of the report's line keys. */
    public com.oriah.finance.dto.EntryPage entries(String month, String line, String product, String category, String orderId, boolean changedSinceIssue, String asOfText, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-page", "page is 0 or more and size is 1 to " + MAX_SIZE);
        }
        YearMonth ym = parse(month);
        Instant asOf = asOf(asOfText);
        Timestamp cut = Timestamp.from(asOf);
        StringBuilder where = new StringBuilder("WHERE effect_month = ? AND recorded_at <= ?");
        List<Object> args = new ArrayList<>(List.of(ym.atDay(1), cut));
        if (line != null && !line.isBlank()) {
            List<String> accounts = LINES.get(line);
            if (accounts == null) {
                throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-line", "'" + line + "' is not a line whose rows can be listed: " + String.join(", ", new TreeMap<>(LINES).keySet()));
            }
            where.append(" AND account IN (").append(String.join(",", accounts.stream().map(a -> "'" + a + "'").toList())).append(")");
        }
        if (product != null && !product.isBlank()) {
            where.append(" AND product_id = ?");
            args.add(uuid(product, "product"));
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            args.add(category);
        }
        if (orderId != null && !orderId.isBlank()) {
            where.append(" AND order_id = ?");
            args.add(uuid(orderId, "orderId"));
        }
        if (changedSinceIssue) {
            where.append(" AND recorded_at > coalesce((SELECT as_of FROM report_issue WHERE month = ? AND issued_at <= ? ORDER BY issue_no DESC LIMIT 1), 'infinity'::timestamptz)");
            args.add(ym.atDay(1));
            args.add(cut);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM ledger_entry " + where, Long.class, args.toArray());
        List<Object> page_args = new ArrayList<>(args);
        page_args.add(size);
        page_args.add((long) page * size);
        List<com.oriah.finance.dto.EntryResponse> items = jdbc.query("""
                SELECT entry_id, entry_key, account, category, reason, amount, effect_day, recorded_at, source, product_id, order_id, confirmation_id, movement_id, estimated, actor
                  FROM ledger_entry""" + " " + where + " ORDER BY recorded_at DESC, entry_id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new com.oriah.finance.dto.EntryResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getDate(7).toLocalDate(),
                        rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getObject(10, UUID.class), rs.getObject(11, UUID.class), rs.getObject(12, UUID.class), (Long) rs.getObject(13),
                        rs.getBoolean(14), rs.getString(15)), page_args.toArray());
        return new com.oriah.finance.dto.EntryPage(items, page, size, total);
    }

    private static UUID uuid(String text, String what) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-" + what, what + " is an id");
        }
    }

    /** The errors (or warnings) that were open at the cut: the latest row of each anomaly AT that moment is a RAISE. */
    private int openAnomalies(String severity, Timestamp cut) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (SELECT DISTINCT ON (code, subject) action, severity FROM anomaly_log WHERE at <= ? ORDER BY code, subject, id DESC) a "
                + "WHERE action = 'RAISE' AND severity = ?", Integer.class, cut, severity);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ by product

    public ProductReportResponse products(String month, String asOfText, int page, int size) {
        YearMonth ym = parse(month);
        return products(ym, asOf(asOfText), page, size);
    }

    ProductReportResponse products(YearMonth ym, Instant asOf, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-page", "page is 0 or more and size is 1 to " + MAX_SIZE);
        }
        return build(ym, asOf, page, size);
    }

    /** Every product row of the month, for the stored copy of a reported month (which must add up to its own total however many products there are). */
    ProductReportResponse allProducts(YearMonth ym, Instant asOf) {
        return build(ym, asOf, 0, Integer.MAX_VALUE);
    }

    private ProductReportResponse build(YearMonth ym, Instant asOf, int page, int size) {
        LocalDate first = ym.atDay(1);
        Timestamp cut = Timestamp.from(asOf);
        Map<UUID, BigDecimal[]> byProduct = new LinkedHashMap<>();
        BigDecimal[] unattributed = new BigDecimal[] {ZERO, ZERO, ZERO, ZERO, ZERO, ZERO};
        jdbc.query("""
                SELECT product_id, account, sum(amount) FROM ledger_entry
                 WHERE effect_month = ? AND recorded_at <= ? AND account IN ('REVENUE_GOODS', 'DISCOUNTS', 'COGS_GOODS', 'COGS_PACKAGING', 'STOCK_OUT') GROUP BY product_id, account""", rs -> {
            UUID product = rs.getObject(1, UUID.class);
            BigDecimal[] cells = product == null ? unattributed : byProduct.computeIfAbsent(product, k -> new BigDecimal[] {ZERO, ZERO, ZERO, ZERO, ZERO, ZERO});
            BigDecimal amount = rs.getBigDecimal(3);
            switch (rs.getString(2)) {
                case "REVENUE_GOODS" -> cells[0] = cells[0].add(amount);
                case "DISCOUNTS" -> cells[1] = cells[1].add(amount);
                case "COGS_GOODS" -> cells[2] = cells[2].add(amount);
                case "COGS_PACKAGING" -> cells[3] = cells[3].add(amount);
                default -> cells[4] = cells[4].add(amount);
            }
        }, first, cut);

        Map<UUID, String> titles = new HashMap<>();
        if (!byProduct.isEmpty()) {
            jdbc.query("SELECT product_id, title FROM product_name", rs -> { titles.put(rs.getObject(1, UUID.class), rs.getString(2)); });
        }
        List<ProductReportResponse.Row> rows = new ArrayList<>();
        BigDecimal[] total = new BigDecimal[] {ZERO, ZERO, ZERO, ZERO, ZERO, ZERO};
        byProduct.forEach((product, c) -> rows.add(row(product, titles.get(product), c)));
        for (BigDecimal[] c : byProduct.values()) {
            add(total, c);
        }
        add(total, unattributed);
        rows.sort(Comparator.comparing(ProductReportResponse.Row::grossProfit).reversed().thenComparing(r -> String.valueOf(r.productId())));
        int from = Math.min(rows.size(), page * size);
        List<ProductReportResponse.Row> pageRows = rows.subList(from, Math.min(rows.size(), from + size));

        ProductReportResponse.Row totals = row(null, null, total);
        BigDecimal reportGross = gross(first, cut);
        BigDecimal difference = totals.grossProfit().subtract(reportGross);
        return new ProductReportResponse(ym.toString(), asOf, pageRows, page, size, rows.size(), totals, row(null, null, unattributed),
                new ProductReportResponse.Checksum(difference.signum() == 0, difference));
    }

    private BigDecimal gross(LocalDate first, Timestamp cut) {
        return nz(jdbc.queryForObject("""
                SELECT sum(CASE account WHEN 'REVENUE_GOODS' THEN amount WHEN 'DISCOUNTS' THEN -amount ELSE -amount END) FROM ledger_entry
                 WHERE effect_month = ? AND recorded_at <= ? AND account IN ('REVENUE_GOODS', 'DISCOUNTS', 'COGS_GOODS', 'COGS_PACKAGING')""", BigDecimal.class, first, cut));
    }

    private static ProductReportResponse.Row row(UUID product, String title, BigDecimal[] c) {
        BigDecimal net = c[0].subtract(c[1]);
        return new ProductReportResponse.Row(product, title, net, c[2], c[3], net.subtract(c[2]).subtract(c[3]), c[4]);
    }

    private static void add(BigDecimal[] into, BigDecimal[] from) {
        for (int i = 0; i < into.length; i++) {
            into[i] = into[i].add(from[i]);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal sum(Map<String, BigDecimal> sums, String account) {
        return sums.getOrDefault(account, ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    /** The cut: what finance knew at this moment (default now; never beyond five minutes ahead, which is clock skew, not a future). */
    Instant asOf(String text) {
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        if (text == null || text.isBlank()) {
            return now;
        }
        try {
            Instant at = Instant.parse(text);
            if (at.isAfter(now.plusSeconds(300))) {
                throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-as-of", "asOf is a moment that has happened: it is not in the future");
            }
            return at;
        } catch (DateTimeParseException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-as-of", "asOf is a moment such as 2026-10-31T23:59:59Z");
        }
    }

    static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new FinanceProblem(HttpStatus.BAD_REQUEST, "invalid-month", "A month is written year-month, for example 2026-10");
        }
    }

    @SuppressWarnings("unused")
    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
