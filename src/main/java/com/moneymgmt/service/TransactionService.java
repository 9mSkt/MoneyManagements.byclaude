package com.moneymgmt.service;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finding, saving and deleting transactions. */
@Service
public class TransactionService {

    /** Most rows shown at once when "all months" is chosen. */
    public static final int MAX_ROWS = 500;

    /** Search filters; every field is optional (null = no filter, month null = all months). */
    public record Filter(YearMonth month, TxType type, BudgetGroup group, Long categoryId, String q) {
        public boolean isFiltered() {
            return type != null || group != null || categoryId != null || (q != null && !q.isBlank());
        }
    }

    public record Totals(BigDecimal income, BigDecimal expense, BigDecimal transfer) {
        /** Income − expense − saving/investment transfers. */
        public BigDecimal remaining() {
            return income.subtract(expense).subtract(transfer);
        }
    }

    public record Result(List<Transaction> rows, Totals totals, boolean truncated) {
    }

    private final TransactionRepository transactions;
    private final com.moneymgmt.repo.InstallmentRepository installments;

    public TransactionService(TransactionRepository transactions,
                              com.moneymgmt.repo.InstallmentRepository installments) {
        this.transactions = transactions;
        this.installments = installments;
    }

    @Transactional(readOnly = true)
    public Result search(Filter f) {
        Specification<Transaction> spec = (root, query, cb) -> {
            // Load the category (and installment) with the rows: pages render after the database session closes.
            jakarta.persistence.criteria.Join<Transaction, ?> category;
            if (query.getResultType() == Transaction.class) {
                category = (jakarta.persistence.criteria.Join<Transaction, ?>) (Object) root.fetch("category", JoinType.INNER);
                root.fetch("installment", JoinType.LEFT);
            } else {
                category = root.join("category", JoinType.INNER);
            }
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            if (f.month() != null) {
                p.add(cb.between(root.get("date"), f.month().atDay(1), f.month().atEndOfMonth()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.group() != null) {
                p.add(cb.equal(category.get("budgetGroup"), f.group()));
            }
            if (f.categoryId() != null) {
                p.add(cb.equal(category.get("id"), f.categoryId()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("description")), like, '\\'),
                        cb.like(cb.lower(root.get("note")), like, '\\'),
                        cb.like(cb.lower(root.get("tags")), like, '\\'),
                        cb.like(cb.lower(category.get("nameEn")), like, '\\'),
                        cb.like(cb.lower(category.get("nameTh")), like, '\\')));
            }
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id"));
        List<Transaction> rows = transactions.findBy(spec, q -> q.sortBy(sort).limit(MAX_ROWS + 1).all());
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }
        return new Result(rows, totals(rows), truncated);
    }

    public static Totals totals(List<Transaction> rows) {
        BigDecimal income = Money.zero();
        BigDecimal expense = Money.zero();
        BigDecimal transfer = Money.zero();
        for (Transaction t : rows) {
            switch (t.getType()) {
                case INCOME -> income = income.add(t.getAmount());
                case EXPENSE -> expense = expense.add(t.getAmount());
                case TRANSFER -> transfer = transfer.add(t.getAmount());
            }
        }
        return new Totals(income, expense, transfer);
    }

    @Transactional
    public Transaction save(Transaction t) {
        t.setAmount(Money.scale(t.getAmount()));
        return transactions.save(t);
    }

    /**
     * Saves a purchase typed with a period such as "1/3" and starts an installment plan from it.
     * {@code fullPrice} is the whole price: the app works out the monthly payment (full price ÷ months, 0 %
     * interest; the owner can change it on the plan page). This entry becomes payment {@code no}, whose amount
     * is the monthly payment plus the optional credit card {@code fee}. Payment {@code no} falls in this entry's
     * month, so the plan started {@code no − 1} months earlier; the app posts the rest on the 1st of each month.
     */
    @Transactional
    public Installment saveAsInstallment(Transaction t, java.math.BigDecimal fullPrice, int no, int months,
                                         java.math.BigDecimal fee) {
        java.math.BigDecimal monthly = Installment.monthlyCharge(Money.scale(fullPrice), months, java.math.BigDecimal.ZERO);
        java.math.BigDecimal paidFee = fee == null || fee.signum() == 0 ? null : Money.scale(fee);
        t.setAmount(paidFee == null ? monthly : monthly.add(paidFee));
        t.setFee(paidFee);
        java.time.LocalDate month = t.getDate().withDayOfMonth(1);
        String item = t.getDescription() != null ? t.getDescription() : t.getCategory().getNameEn();
        Installment plan = new Installment(item, Money.scale(fullPrice), monthly, months, month.minusMonths(no - 1L));
        plan.setCategory(t.getCategory());
        plan.setPaidCount(no);
        plan.setLastPostedMonth(month);
        installments.save(plan);
        t.setInstallment(plan);
        t.setInstallmentNo(no);
        transactions.save(t);
        return plan;
    }

    /** True when an entry can be turned into an installment plan: a positive expense, not a fixed cost or plan payment. */
    public static boolean canSplit(Transaction t) {
        return t.getType() == TxType.EXPENSE && t.getAmount().signum() > 0 && t.getInstallment() == null
                && t.getRecurringItem() == null && t.getCategory().getBudgetGroup() != BudgetGroup.FIXED;
    }

    /**
     * Credit card campaign ("0 % for 3 months"): each chosen purchase becomes its own 0 % plan of {@code months}
     * payments. The entry keeps its date and becomes payment 1 (amount = price ÷ months); the app posts the
     * other payments in the next months. Entries that cannot be split are skipped. Returns how many were split.
     */
    @Transactional
    public int splitIntoInstallments(List<Long> ids, int months) {
        if (months < 2 || months > 120) {
            throw new IllegalArgumentException("months must be 2 to 120");
        }
        int done = 0;
        for (Long id : ids) {
            Transaction t = transactions.findWithDetailsById(id).orElse(null);
            if (t == null || !canSplit(t)) {
                continue;
            }
            saveAsInstallment(t, t.getAmount(), 1, months, null);
            done++;
        }
        return done;
    }

    /**
     * Deletes one transaction. If it was the latest payment of an installment plan, the plan's paid count
     * goes back by one (the app will not post that payment again by itself; use "Post" on Fixed costs).
     */
    @Transactional
    public void delete(Long id) {
        transactions.findWithDetailsById(id).ifPresent(t -> {
            Installment plan = t.getInstallment();
            if (plan != null && t.getInstallmentNo() != null && t.getInstallmentNo() == plan.getPaidCount()
                    && plan.getPaidCount() > 0) {
                plan.setPaidCount(plan.getPaidCount() - 1);
            }
            transactions.delete(t);
        });
    }
}
