package com.moneymgmt.service;

import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.repo.RecurringItemRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.AppTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts fixed costs (recurring items) and installment payments as transactions, once per month.
 *
 * <p>Automatic posting covers the current month only: a recurring item is posted on or after its day of the
 * month, an installment payment on the 1st. An item counts as already posted when a transaction linked to it
 * exists that month, or one with the same category and name (for example from the sheet import). Each item
 * remembers the last month it was posted, so a posting the owner deletes is not posted again.
 */
@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    /** One recurring item in a month: posted transaction, or null if not posted yet. */
    public record ItemStatus(RecurringItem item, LocalDate dueDate, Transaction posted) {
    }

    /** One installment plan in a month: posted payment, or null if not posted yet. */
    public record PlanStatus(Installment plan, Transaction posted, int nextNo) {
    }

    public record MonthStatus(YearMonth month, List<ItemStatus> items, List<PlanStatus> plans) {
    }

    private final RecurringItemRepository items;
    private final InstallmentRepository plans;
    private final TransactionRepository transactions;
    private final boolean autoPost;
    private volatile LocalDate lastAutoRun;

    public PostingService(RecurringItemRepository items, InstallmentRepository plans,
                          TransactionRepository transactions, @Value("${app.posting.auto:true}") boolean autoPost) {
        this.items = items;
        this.plans = plans;
        this.transactions = transactions;
        this.autoPost = autoPost;
    }

    /** Runs {@link #postDue} at most once a day; called when the main pages open (the free host sleeps at night). */
    @Transactional
    public synchronized void autoPostIfNeeded() {
        LocalDate today = AppTime.today();
        if (!autoPost || today.equals(lastAutoRun)) {
            return;
        }
        int posted = postDue(today);
        if (posted > 0) {
            log.info("Posted {} fixed costs / installment payments for {}", posted, YearMonth.from(today));
        }
        lastAutoRun = today;
    }

    /** Check again on the next page view (after an item is added or changed). */
    public void recheck() {
        lastAutoRun = null;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Bangkok")
    public void nightly() {
        recheck();
        autoPostIfNeeded();
    }

    /** Posts everything due in the month of {@code today}, up to and including today. Returns how many. */
    @Transactional
    public int postDue(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        LocalDate first = month.atDay(1);
        int count = 0;
        for (RecurringItem item : items.findByActiveTrue()) {
            if (today.isBefore(AppTime.dayIn(month, item.getDayOfMonth())) || postedFrom(item.getLastPostedMonth(), first)) {
                continue;
            }
            if (findPosted(item, month) != null) {
                item.setLastPostedMonth(first);
                continue;
            }
            post(item, month);
            count++;
        }
        for (Installment plan : plans.findAll()) {
            if (plan.isFinished() || plan.getCategory() == null || !plan.hasPaymentIn(month)
                    || postedFrom(plan.getLastPostedMonth(), first)) {
                continue;
            }
            if (findPosted(plan, month) != null) {
                plan.setLastPostedMonth(first);
                continue;
            }
            post(plan, month);
            count++;
        }
        return count;
    }

    /** Posts one recurring item for a month by hand (Fixed costs page). Does nothing if already posted. */
    @Transactional
    public void postItem(Long id, YearMonth month) {
        items.findWithCategoryById(id).ifPresent(item -> {
            if (findPosted(item, month) == null) {
                post(item, month);
            }
        });
    }

    /** Posts the next payment of a plan for a month by hand. Does nothing if paid off or already posted. */
    @Transactional
    public void postPlan(Long id, YearMonth month) {
        plans.findWithCategoryById(id).ifPresent(plan -> {
            if (plan.hasPaymentIn(month) && plan.getCategory() != null && findPosted(plan, month) == null) {
                post(plan, month);
            }
        });
    }

    @Transactional(readOnly = true)
    public MonthStatus status(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<RecurringItem> active = new ArrayList<>(items.findByActiveTrue());
        active.sort(java.util.Comparator.comparingInt(RecurringItem::getDayOfMonth)
                .thenComparingInt(r -> r.getCategory().getSortOrder()).thenComparing(RecurringItem::getName));
        Map<Long, Transaction> linked = new HashMap<>();
        if (!active.isEmpty()) {
            transactions.findByRecurringItemInAndDateBetween(active, from, to)
                    .forEach(t -> linked.putIfAbsent(t.getRecurringItem().getId(), t));
        }
        List<ItemStatus> itemRows = new ArrayList<>();
        for (RecurringItem item : active) {
            Transaction posted = linked.get(item.getId());
            if (posted == null) {
                posted = sameInMonth(item.getCategory(), item.getName(), month);
            }
            itemRows.add(new ItemStatus(item, AppTime.dayIn(month, item.getDayOfMonth()), posted));
        }

        List<Installment> all = plans.findAllForList();
        Map<Long, Transaction> payments = new HashMap<>();
        if (!all.isEmpty()) {
            transactions.findByInstallmentInAndDateBetween(all, from, to)
                    .forEach(t -> payments.putIfAbsent(t.getInstallment().getId(), t));
        }
        List<PlanStatus> planRows = new ArrayList<>();
        for (Installment plan : all) {
            Transaction posted = payments.get(plan.getId());
            if (posted == null && plan.getCategory() != null) {
                posted = sameInMonth(plan.getCategory(), plan.getItem(), month);
            }
            boolean running = plan.hasPaymentIn(month);
            if (posted != null || running) {
                planRows.add(new PlanStatus(plan, posted, plan.paymentNoIn(month)));
            }
        }
        return new MonthStatus(month, itemRows, planRows);
    }

    /** A fixed cost or installment payment still to come. {@code detail} is "3/10" for installments. */
    public record Upcoming(boolean installment, String name, String detail, LocalDate due, java.math.BigDecimal amount) {
    }

    /**
     * Fixed costs and installment payments due from today up to {@code days} ahead, soonest first:
     * this month's items not posted yet, then next month's.
     */
    @Transactional(readOnly = true)
    public List<Upcoming> upcoming(LocalDate today, int days) {
        LocalDate until = today.plusDays(days);
        YearMonth month = YearMonth.from(today);
        MonthStatus now = status(month);
        List<Upcoming> out = new ArrayList<>();
        for (ItemStatus s : now.items()) {
            if (s.item().getCategory().getType() != TxType.EXPENSE) {
                continue;
            }
            LocalDate due = s.posted() == null && !s.dueDate().isBefore(today) ? s.dueDate()
                    : AppTime.dayIn(month.plusMonths(1), s.item().getDayOfMonth());
            if (!due.isAfter(until)) {
                out.add(new Upcoming(false, s.item().getName(), null, due, s.item().getAmount()));
            }
        }
        for (Installment plan : plans.findAllForList()) {
            if (plan.isFinished() || plan.getCategory() == null) {
                continue;
            }
            // The next payment is posted on the 1st of the month after the last one posted.
            LocalDate last = plan.getLastPostedMonth();
            LocalDate due;
            if (last != null) {
                due = last.plusMonths(1);
            } else if (plan.getStartMonth().isAfter(month.atDay(1))) {
                due = plan.getStartMonth();
            } else {
                due = month.plusMonths(1).atDay(1);
            }
            if (due.isBefore(today)) {
                due = month.plusMonths(1).atDay(1);
            }
            YearMonth dueMonth = YearMonth.from(due);
            if (!due.isAfter(until) && plan.hasPaymentIn(dueMonth)) {
                out.add(new Upcoming(true, plan.getItem(), plan.paymentNoIn(dueMonth) + "/" + plan.getMonths(), due,
                        plan.getMonthlyAmount()));
            }
        }
        out.sort(java.util.Comparator.comparing(Upcoming::due).thenComparing(Upcoming::name));
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    private static boolean postedFrom(LocalDate lastPosted, LocalDate first) {
        return lastPosted != null && !lastPosted.isBefore(first);
    }

    private Transaction findPosted(RecurringItem item, YearMonth month) {
        List<Transaction> linked = transactions.findByRecurringItemInAndDateBetween(List.of(item), month.atDay(1),
                month.atEndOfMonth());
        return linked.isEmpty() ? sameInMonth(item.getCategory(), item.getName(), month) : linked.get(0);
    }

    private Transaction findPosted(Installment plan, YearMonth month) {
        List<Transaction> linked = transactions.findByInstallmentInAndDateBetween(List.of(plan), month.atDay(1),
                month.atEndOfMonth());
        if (!linked.isEmpty()) {
            return linked.get(0);
        }
        return plan.getCategory() == null ? null : sameInMonth(plan.getCategory(), plan.getItem(), month);
    }

    private Transaction sameInMonth(com.moneymgmt.domain.Category category, String name, YearMonth month) {
        List<Transaction> same = transactions.findSame(category, name, month.atDay(1), month.atEndOfMonth());
        return same.isEmpty() ? null : same.get(0);
    }

    private void post(RecurringItem item, YearMonth month) {
        Transaction t = new Transaction(AppTime.dayIn(month, item.getDayOfMonth()), item.getCategory().getType(),
                item.getAmount(), item.getCategory(), item.getName());
        t.setRecurringItem(item);
        t.setAccount(item.getAccount());
        transactions.save(t);
        item.setLastPostedMonth(later(item.getLastPostedMonth(), month.atDay(1)));
    }

    /**
     * Posts the payment that falls in {@code month}. Its number comes from the month (payment 1 is in the first
     * payment month), so numbers always match the schedule; months skipped before it count as paid.
     */
    private void post(Installment plan, YearMonth month) {
        int no = plan.paymentNoIn(month);
        Transaction t = new Transaction(month.atDay(1), TxType.EXPENSE, plan.getMonthlyAmount(), plan.getCategory(),
                plan.getItem());
        t.setInstallment(plan);
        t.setInstallmentNo(no);
        t.setNote(no + "/" + plan.getMonths());
        transactions.save(t);
        plan.setPaidCount(Math.max(plan.getPaidCount(), no));
        plan.setLastPostedMonth(later(plan.getLastPostedMonth(), month.atDay(1)));
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
