package com.moneymgmt.service;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryMonthTotal;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Totals per month, category and group. The database adds up the amounts; nothing here is stored. */
@Service
public class StatsService {

    /** One category's total in one month. */
    public record CatTotal(YearMonth month, Category category, TxType type, BigDecimal amount) {
    }

    /** A month at a glance. */
    public record MonthSummary(YearMonth month, BigDecimal income, BigDecimal expense, BigDecimal transfer,
                               BigDecimal fixedCost) {
        /** Cash left after spending, saving and investing. */
        public BigDecimal remaining() {
            return income.subtract(expense).subtract(transfer);
        }

        /** Share of income not spent (saved, invested or left over), in %. */
        public BigDecimal savingRate() {
            return Money.percent(income.subtract(expense), income);
        }

        /** Income after fixed cost: what the budgets are worked out from (never below 0). */
        public BigDecimal base() {
            return income.subtract(fixedCost).max(Money.zero());
        }

        public boolean isEmpty() {
            return income.signum() == 0 && expense.signum() == 0 && transfer.signum() == 0;
        }
    }

    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    /** Day of the month the salary is paid; income from this day on is next month's money (0 = off). */
    private final int payday;

    public StatsService(TransactionRepository transactions, CategoryRepository categories,
                        @org.springframework.beans.factory.annotation.Value("${app.budget.payday:28}") int payday) {
        this.transactions = transactions;
        this.categories = categories;
        this.payday = payday;
    }

    /**
     * Totals per month and category. Income received on or after {@link #payday} counts toward the next month
     * (the salary paid on the 28th of October is the money for November); expenses count in their own month.
     */
    @Transactional(readOnly = true)
    public List<CatTotal> totals(YearMonth from, YearMonth to) {
        Map<Long, Category> cats = categories.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<CatTotal> rows = new ArrayList<>();
        for (CategoryMonthTotal r : transactions.totalsByMonthAndCategory(from.atDay(1), to.atEndOfMonth())) {
            rows.add(new CatTotal(YearMonth.of(r.year(), r.month()), cats.get(r.categoryId()), r.type(),
                    Money.scale(r.amount())));
        }
        Map<YearMonth, Map<Long, BigDecimal>> income = new LinkedHashMap<>();
        for (Object[] r : transactions.incomeByDayAndCategory(from.minusMonths(1).atDay(1), to.atEndOfMonth())) {
            YearMonth m = budgetMonth((LocalDate) r[0], payday);
            if (!m.isBefore(from) && !m.isAfter(to)) {
                income.computeIfAbsent(m, k -> new LinkedHashMap<>()).merge((Long) r[1], (BigDecimal) r[2], BigDecimal::add);
            }
        }
        income.forEach((m, byCat) -> byCat.forEach((catId, sum) ->
                rows.add(new CatTotal(m, cats.get(catId), TxType.INCOME, Money.scale(sum)))));
        return rows;
    }

    /** The month an income dated {@code date} is the money for: the next month from that month's payday on. */
    public static YearMonth budgetMonth(LocalDate date, int payday) {
        YearMonth m = YearMonth.from(date);
        LocalDate pay = payDate(m, payday);
        return pay != null && !date.isBefore(pay) ? m.plusMonths(1) : m;
    }

    /**
     * The day the salary is paid in a month: the payday (28th), or the last weekday before it when it falls
     * on a Saturday or Sunday. Null when the payday rule is off.
     */
    public static LocalDate payDate(YearMonth month, int payday) {
        if (payday < 1) {
            return null;
        }
        LocalDate d = month.atDay(Math.min(payday, month.lengthOfMonth()));
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    public int getPayday() {
        return payday;
    }

    public MonthSummary summary(YearMonth month) {
        return months(month, month).get(0);
    }

    /** One summary per month from {@code from} to {@code to}, months without entries included (all zero). */
    @Transactional(readOnly = true)
    public List<MonthSummary> months(YearMonth from, YearMonth to) {
        return summarize(totals(from, to), from, to);
    }

    public static List<MonthSummary> summarize(List<CatTotal> rows, YearMonth from, YearMonth to) {
        Map<YearMonth, BigDecimal[]> acc = new LinkedHashMap<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            acc.put(m, new BigDecimal[] {Money.zero(), Money.zero(), Money.zero(), Money.zero()});
        }
        for (CatTotal r : rows) {
            BigDecimal[] a = acc.get(r.month());
            if (a == null) {
                continue;
            }
            switch (r.type()) {
                case INCOME -> a[0] = a[0].add(r.amount());
                case EXPENSE -> {
                    a[1] = a[1].add(r.amount());
                    if (r.category().getBudgetGroup() == BudgetGroup.FIXED) {
                        a[3] = a[3].add(r.amount());
                    }
                }
                case TRANSFER -> a[2] = a[2].add(r.amount());
            }
        }
        List<MonthSummary> out = new ArrayList<>();
        acc.forEach((m, a) -> out.add(new MonthSummary(m, a[0], a[1], a[2], a[3])));
        return out;
    }

    /** Spending (expenses and saving/investing transfers) per budget group in a month. */
    public static Map<BudgetGroup, BigDecimal> byGroup(List<CatTotal> rows) {
        Map<BudgetGroup, BigDecimal> out = new EnumMap<>(BudgetGroup.class);
        for (CatTotal r : rows) {
            BudgetGroup g = r.category().getBudgetGroup();
            if (r.type() != TxType.INCOME && g != null) {
                out.merge(g, r.amount(), BigDecimal::add);
            }
        }
        return out;
    }

    /** Spending per day without fixed costs; days with nothing are left out. */
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> dailySpending(YearMonth month) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (Object[] row : transactions.dailySpending(month.atDay(1), month.atEndOfMonth())) {
            out.put((LocalDate) row[0], Money.scale((BigDecimal) row[1]));
        }
        return out;
    }
}
