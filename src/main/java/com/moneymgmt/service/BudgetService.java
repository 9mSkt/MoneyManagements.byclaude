package com.moneymgmt.service;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.BudgetKind;
import com.moneymgmt.domain.BudgetRule;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.BudgetRuleRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two budgets per month, both worked out from income after fixed cost: the ACTUAL budget (from the month tabs,
 * e.g. Eat 40 / Shopping 40 / Gas 20) and the GOAL budget (from the Summary Report, 10/15/15/15/15/30).
 *
 * <p>A rule applies from its month onward until a later rule for the same group replaces it, so each month
 * starts as a copy of the month before. A rule with neither percent nor amount means "no budget".
 */
@Service
public class BudgetService {

    /** Groups that can have a budget (fixed costs are what the budget is taken after). */
    public static final List<BudgetGroup> GROUPS = List.of(BudgetGroup.GAS, BudgetGroup.NECESSARY,
            BudgetGroup.SHOPPING, BudgetGroup.EAT, BudgetGroup.SAVING, BudgetGroup.INVESTMENT);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal WARN_AT = BigDecimal.valueOf(80);

    /** One group's budget in a month. */
    public record GroupBudget(BudgetGroup group, BigDecimal percent, BigDecimal fixedAmount, BigDecimal budget,
                              BigDecimal spent) {

        public boolean hasBudget() {
            return budget != null;
        }

        /** Saving and investing are targets to reach, not limits: going past 100 % is good. */
        public boolean isTarget() {
            return group == BudgetGroup.SAVING || group == BudgetGroup.INVESTMENT;
        }

        public BigDecimal usedPercent() {
            if (budget == null) {
                return Money.zero();
            }
            if (budget.signum() == 0) {
                return spent.signum() > 0 ? BigDecimal.valueOf(999) : Money.zero();
            }
            return Money.percent(spent, budget);
        }

        /** ok below 80 %, warn 80–100 %, over above 100 %; targets: goal (not reached yet) or done. */
        public String level() {
            BigDecimal used = usedPercent();
            if (isTarget()) {
                return budget != null && spent.compareTo(budget) >= 0 ? "done" : "goal";
            }
            // Compare the baht amounts, not the rounded percent: ฿0.01 over is over.
            if (budget != null && spent.compareTo(budget) > 0) {
                return "over";
            }
            return used.compareTo(WARN_AT) >= 0 ? "warn" : "ok";
        }

        /** A budget of ฿0 with spending: there is no meaningful percent to show. */
        public boolean isZeroBudgetSpent() {
            return budget != null && budget.signum() == 0 && spent.signum() > 0;
        }

        public BigDecimal width() {
            return usedPercent().min(HUNDRED).max(BigDecimal.ZERO);
        }

        /** Budget − spent (negative when over). */
        public BigDecimal left() {
            return budget == null ? null : budget.subtract(spent);
        }

        public boolean isByAmount() {
            return fixedAmount != null;
        }
    }

    public record KindBudget(BudgetKind kind, List<GroupBudget> groups) {
        public BigDecimal totalBudget() {
            return groups.stream().filter(GroupBudget::hasBudget).filter(g -> !g.isTarget())
                    .map(GroupBudget::budget).reduce(Money.zero(), BigDecimal::add);
        }

        public BigDecimal totalSpent() {
            return groups.stream().filter(GroupBudget::hasBudget).filter(g -> !g.isTarget())
                    .map(GroupBudget::spent).reduce(Money.zero(), BigDecimal::add);
        }

        /** Sum of percent rules (to show "adds up to 100 %"). */
        public BigDecimal totalPercent() {
            return groups.stream().map(GroupBudget::percent).filter(Objects::nonNull)
                    .reduce(Money.zero(), BigDecimal::add);
        }

        public boolean isOverHundredPercent() {
            return totalPercent().compareTo(HUNDRED) > 0;
        }

        public List<GroupBudget> withBudget() {
            return groups.stream().filter(GroupBudget::hasBudget).toList();
        }

        /** Groups with spending but no budget set. */
        public List<GroupBudget> unbudgeted() {
            return groups.stream().filter(g -> !g.hasBudget() && g.spent().signum() != 0).toList();
        }
    }

    /**
     * {@code base} is what the budgets are worked out from: income after fixed cost of the month, or (while the
     * month has no income yet, before payday) last month's, with {@code estimated} = true.
     */
    public record MonthBudget(YearMonth month, StatsService.MonthSummary summary, KindBudget actual,
                              KindBudget goal, BigDecimal base, boolean estimated) {
        public KindBudget of(BudgetKind kind) {
            return kind == BudgetKind.GOAL ? goal : actual;
        }
    }

    /** What the owner typed for one group: a percent, a baht amount, or nothing (no budget). */
    public record Setting(BigDecimal percent, BigDecimal fixedAmount) {
        public static final Setting NONE = new Setting(null, null);

        boolean sameAs(BudgetRule r) {
            BigDecimal p = r == null ? null : r.getPercent();
            BigDecimal a = r == null ? null : r.getFixedAmount();
            return equal(percent, p) && equal(fixedAmount, a);
        }

        private static boolean equal(BigDecimal x, BigDecimal y) {
            return x == null ? y == null : y != null && x.compareTo(y) == 0;
        }
    }

    private final BudgetRuleRepository rules;
    private final StatsService stats;
    private final TransactionRepository transactions;

    public BudgetService(BudgetRuleRepository rules, StatsService stats, TransactionRepository transactions) {
        this.rules = rules;
        this.stats = stats;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public MonthBudget forMonth(YearMonth month) {
        var rows = stats.totals(month.minusMonths(1), month);
        var both = StatsService.summarize(rows, month.minusMonths(1), month);
        var summary = both.get(1);
        var previous = both.get(0);
        // The salary arrives on the 28th: until this month has income, budget from last month's income after fixed cost.
        boolean estimated = summary.income().signum() == 0 && previous.income().signum() > 0;
        BigDecimal base = estimated ? previous.base() : summary.base();
        Map<BudgetGroup, BigDecimal> spent = StatsService.byGroup(rows.stream().filter(r -> r.month().equals(month)).toList());
        return new MonthBudget(month, summary, kind(BudgetKind.ACTUAL, month, base, spent),
                kind(BudgetKind.GOAL, month, base, spent), base, estimated);
    }

    /** The rule in force per group for a month (missing = never set). */
    @Transactional(readOnly = true)
    public Map<BudgetGroup, BudgetRule> rulesInForce(BudgetKind kind, YearMonth month) {
        Map<BudgetGroup, BudgetRule> map = new EnumMap<>(BudgetGroup.class);
        for (BudgetRule r : rules.findByKindAndMonthLessThanEqualOrderByMonthAsc(kind, month.atDay(1))) {
            map.put(r.getGroup(), r);
        }
        return map;
    }

    private KindBudget kind(BudgetKind kind, YearMonth month, BigDecimal base, Map<BudgetGroup, BigDecimal> spent) {
        Map<BudgetGroup, BudgetRule> inForce = rulesInForce(kind, month);
        List<GroupBudget> groups = new ArrayList<>();
        for (BudgetGroup g : GROUPS) {
            BudgetRule r = inForce.get(g);
            BigDecimal percent = r == null ? null : r.getPercent();
            BigDecimal fixed = r == null ? null : r.getFixedAmount();
            BigDecimal budget = null;
            if (fixed != null) {
                budget = Money.scale(fixed);
            } else if (percent != null) {
                budget = Money.scale(base.multiply(percent).divide(HUNDRED, 6, Money.ROUNDING));
            }
            groups.add(new GroupBudget(g, percent, fixed, budget, spent.getOrDefault(g, Money.zero())));
        }
        return new KindBudget(kind, groups);
    }

    /**
     * Saves the budget of one kind for a month. {@code laterToo} = also for every later month (later changes
     * are removed); otherwise only this month changes and the next month goes back to the earlier value.
     */
    @Transactional
    public void save(BudgetKind kind, YearMonth month, Map<BudgetGroup, Setting> settings, boolean laterToo) {
        LocalDate first = month.atDay(1);
        LocalDate next = month.plusMonths(1).atDay(1);
        Map<BudgetGroup, BudgetRule> before = rulesInForce(kind, month);
        for (BudgetGroup g : GROUPS) {
            Setting s = settings.getOrDefault(g, Setting.NONE);
            BudgetRule old = before.get(g);
            if (laterToo) {
                rules.deleteAll(rules.findByKindAndGroupAndMonthGreaterThan(kind, g, first));
            } else if (s.sameAs(old)) {
                continue;
            }
            if (!laterToo || !s.sameAs(old)) {
                upsert(kind, g, first, s);
            }
            if (!laterToo && rules.findByMonthAndKindAndGroup(next, kind, g).isEmpty()) {
                // Keep next month as it was before this change.
                upsert(kind, g, next, old == null ? Setting.NONE
                        : new Setting(old.getPercent(), old.getFixedAmount()));
            }
        }
    }

    private void upsert(BudgetKind kind, BudgetGroup group, LocalDate month, Setting s) {
        BudgetRule rule = rules.findByMonthAndKindAndGroup(month, kind, group)
                .orElseGet(() -> new BudgetRule(month, kind, group, null, null));
        rule.setPercent(s.percent());
        rule.setFixedAmount(s.fixedAmount() == null ? null : Money.scale(s.fixedAmount()));
        rule.setImportBatch(null);  // the owner's own setting: a new sheet import must not remove it
        rules.save(rule);
    }

    /**
     * Entries that pushed a group over its ACTUAL budget in a month: going through the month in date order,
     * the entry that crosses the budget and every later entry of that group. Saving and investing are left out.
     */
    @Transactional(readOnly = true)
    public Set<Long> overBudgetIds(YearMonth month) {
        KindBudget actual = forMonth(month).actual();
        Map<BudgetGroup, BigDecimal> limits = new HashMap<>();
        for (GroupBudget g : actual.groups()) {
            if (g.hasBudget() && !g.isTarget()) {
                limits.put(g.group(), g.budget());
            }
        }
        Set<Long> over = new LinkedHashSet<>();
        if (limits.isEmpty()) {
            return over;
        }
        Map<BudgetGroup, BigDecimal> running = new EnumMap<>(BudgetGroup.class);
        for (Transaction t : transactions.findByDateBetweenOrderByDateAscIdAsc(month.atDay(1), month.atEndOfMonth())) {
            BudgetGroup g = t.getCategory().getBudgetGroup();
            if (t.getType() != TxType.EXPENSE || g == null || !limits.containsKey(g)) {
                continue;
            }
            BigDecimal sum = running.merge(g, t.getAmount(), BigDecimal::add);
            if (sum.compareTo(limits.get(g)) > 0 && t.getAmount().signum() > 0) {
                over.add(t.getId());
            }
        }
        return over;
    }
}
