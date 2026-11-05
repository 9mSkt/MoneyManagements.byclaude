package com.moneymgmt.service;

import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly incentive entry: one Incentive transaction per month (with the KPI % and incentive % typed in)
 * and an optional Bonus transaction. Nothing is calculated; the owner types the numbers from the pay slip.
 */
@Service
public class IncentiveService {

    public static final String INCENTIVE = "INCENTIVE";
    public static final String BONUS = "BONUS";

    /** The month's entry; either transaction may be null. */
    public record Entry(YearMonth month, Transaction incentive, Transaction bonus) {
        public boolean isEmpty() {
            return incentive == null && bonus == null;
        }
    }

    /** Values to save, already checked. Bonus null or zero removes the month's bonus. */
    public record Input(YearMonth month, LocalDate date, BigDecimal amount, BigDecimal kpiPercent,
                        BigDecimal incentivePercent, BigDecimal bonus, String note) {
    }

    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final int payday;

    public IncentiveService(TransactionRepository transactions, CategoryRepository categories,
                            @org.springframework.beans.factory.annotation.Value("${app.budget.payday:28}") int payday) {
        this.transactions = transactions;
        this.categories = categories;
        this.payday = payday;
    }

    /**
     * The days whose income is the money for {@code month}: from last month's payday to the day before this
     * month's payday (the whole calendar month when the payday rule is off).
     */
    public LocalDate[] payPeriod(YearMonth month) {
        return StatsService.payPeriod(month, payday);
    }

    /** The month an income dated {@code date} counts toward. */
    public YearMonth monthOf(LocalDate date) {
        return StatsService.budgetMonth(date, payday);
    }

    @Transactional(readOnly = true)
    public Entry forMonth(YearMonth month) {
        return new Entry(month, first(INCENTIVE, month), first(BONUS, month));
    }

    /** Incentive entries of the last {@code months} months, newest first. */
    @Transactional(readOnly = true)
    public List<Transaction> history(int months) {
        YearMonth now = com.moneymgmt.util.AppTime.thisMonth();
        List<Transaction> rows = new ArrayList<>(transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc(
                INCENTIVE, payPeriod(now.minusMonths(months - 1L))[0], payPeriod(now)[1]));
        Collections.reverse(rows);
        return rows;
    }

    @Transactional
    public void save(Input in) {
        Category incentiveCat = categories.findByCode(INCENTIVE).orElseThrow();
        Transaction t = first(INCENTIVE, in.month());
        if (t == null) {
            t = new Transaction(in.date(), incentiveCat.getType(), in.amount(), incentiveCat, null);
        }
        t.setDate(in.date());
        t.setAmount(com.moneymgmt.util.Money.scale(in.amount()));
        t.setKpiPercent(in.kpiPercent());
        t.setIncentivePercent(in.incentivePercent());
        t.setNote(in.note());
        transactions.save(t);

        Transaction b = first(BONUS, in.month());
        if (in.bonus() == null || in.bonus().signum() == 0) {
            if (b != null) {
                transactions.delete(b);
            }
            return;
        }
        if (b == null) {
            Category bonusCat = categories.findByCode(BONUS).orElseThrow();
            b = new Transaction(in.date(), bonusCat.getType(), in.bonus(), bonusCat, null);
        }
        b.setDate(in.date());
        b.setAmount(com.moneymgmt.util.Money.scale(in.bonus()));
        transactions.save(b);
    }

    private Transaction first(String code, YearMonth month) {
        LocalDate[] p = payPeriod(month);
        List<Transaction> rows = transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc(code, p[0], p[1]);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
