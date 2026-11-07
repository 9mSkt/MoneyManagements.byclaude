package com.moneymgmt.service;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.service.StatsService.CatTotal;
import com.moneymgmt.service.StatsService.MonthSummary;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Yearly report (like the sheet's Summary Report, but always computed) and month-to-month compare. */
@Service
public class ReportService {

    /** Groups shown as columns of the yearly report, in this order. */
    public static final List<BudgetGroup> GROUPS = List.of(BudgetGroup.FIXED, BudgetGroup.GAS, BudgetGroup.NECESSARY,
            BudgetGroup.SHOPPING, BudgetGroup.EAT, BudgetGroup.SAVING, BudgetGroup.INVESTMENT);

    public record YearRow(YearMonth month, MonthSummary summary, Map<BudgetGroup, BigDecimal> groups) {
        public BigDecimal group(BudgetGroup g) {
            return groups.getOrDefault(g, Money.zero());
        }
    }

    public record YearReport(int year, List<YearRow> rows, YearRow total) {
        public boolean isEmpty() {
            return rows.stream().allMatch(r -> r.summary().isEmpty());
        }

        /** Average per month over the months that have entries. */
        public BigDecimal averageIncome() {
            long n = rows.stream().filter(r -> !r.summary().isEmpty()).count();
            return n == 0 ? Money.zero()
                    : total.summary().income().divide(BigDecimal.valueOf(n), Money.SCALE, Money.ROUNDING);
        }

        public BigDecimal averageExpense() {
            long n = rows.stream().filter(r -> !r.summary().isEmpty()).count();
            return n == 0 ? Money.zero()
                    : total.summary().expense().divide(BigDecimal.valueOf(n), Money.SCALE, Money.ROUNDING);
        }
    }

    /** One category in the compare table: amount in month A and month B. */
    public record CompareRow(Category category, TxType type, BigDecimal a, BigDecimal b) {
        public BigDecimal diff() {
            return b.subtract(a);
        }

        /** Change in %, or null when month A is zero. */
        public BigDecimal diffPercent() {
            return a.signum() == 0 ? null : Money.percent(diff(), a);
        }
    }

    public record Compare(YearMonth a, YearMonth b, MonthSummary summaryA, MonthSummary summaryB,
                          List<CompareRow> rows) {
        public List<CompareRow> of(TxType type) {
            return rows.stream().filter(r -> r.type() == type).toList();
        }
    }

    private final StatsService stats;

    public ReportService(StatsService stats) {
        this.stats = stats;
    }

    public YearReport year(int year) {
        YearMonth from = YearMonth.of(year, 1);
        YearMonth to = YearMonth.of(year, 12);
        List<CatTotal> all = stats.totals(from, to);
        List<MonthSummary> months = StatsService.summarize(all, from, to);
        List<YearRow> rows = new ArrayList<>();
        Map<BudgetGroup, BigDecimal> totalGroups = new EnumMap<>(BudgetGroup.class);
        for (MonthSummary s : months) {
            List<CatTotal> inMonth = all.stream().filter(r -> r.month().equals(s.month())).toList();
            Map<BudgetGroup, BigDecimal> groups = StatsService.byGroup(inMonth);
            groups.forEach((g, v) -> totalGroups.merge(g, v, BigDecimal::add));
            rows.add(new YearRow(s.month(), s, groups));
        }
        BigDecimal[] sum = {Money.zero(), Money.zero(), Money.zero(), Money.zero()};
        for (MonthSummary s : months) {
            sum[0] = sum[0].add(s.income());
            sum[1] = sum[1].add(s.expense());
            sum[2] = sum[2].add(s.transfer());
            sum[3] = sum[3].add(s.fixedCost());
        }
        YearRow total = new YearRow(null, new MonthSummary(null, sum[0], sum[1], sum[2], sum[3]), totalGroups);
        return new YearReport(year, rows, total);
    }

    public Compare compare(YearMonth a, YearMonth b) {
        YearMonth from = a.isBefore(b) ? a : b;
        YearMonth to = a.isBefore(b) ? b : a;
        List<CatTotal> all = stats.totals(from, to);
        Map<Long, CompareRow> byCat = new LinkedHashMap<>();
        for (CatTotal r : all) {
            if (!r.month().equals(a) && !r.month().equals(b)) {
                continue;
            }
            CompareRow row = byCat.getOrDefault(r.category().getId(),
                    new CompareRow(r.category(), r.type(), Money.zero(), Money.zero()));
            row = r.month().equals(a)
                    ? new CompareRow(row.category(), row.type(), row.a().add(r.amount()), row.b())
                    : new CompareRow(row.category(), row.type(), row.a(), row.b().add(r.amount()));
            byCat.put(r.category().getId(), row);
        }
        List<CompareRow> rows = new ArrayList<>(byCat.values());
        rows.sort(Comparator.comparingInt((CompareRow r) -> r.category().getSortOrder())
                .thenComparing(r -> r.category().getCode()));
        List<MonthSummary> sums = StatsService.summarize(all, from, to);
        MonthSummary sa = sums.stream().filter(s -> s.month().equals(a)).findFirst().orElseThrow();
        MonthSummary sb = sums.stream().filter(s -> s.month().equals(b)).findFirst().orElseThrow();
        return new Compare(a, b, sa, sb, rows);
    }
}
