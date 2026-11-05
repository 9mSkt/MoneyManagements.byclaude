package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.service.BudgetService;
import com.moneymgmt.service.PostingService;
import com.moneymgmt.service.StatsService;
import com.moneymgmt.service.StatsService.CatTotal;
import com.moneymgmt.service.StatsService.MonthSummary;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** The month at a glance. Numbers on the page are rendered by the server; charts load a small JSON call. */
@Controller
public class DashboardController {

    /** How far ahead "upcoming installments and bills" looks. */
    static final int UPCOMING_DAYS = 35;

    public record Kpi(String key, BigDecimal value, String kind, boolean percent) {
    }

    private final StatsService stats;
    private final BudgetService budgets;
    private final PostingService posting;
    private final MessageSource messages;

    public DashboardController(StatsService stats, BudgetService budgets, PostingService posting,
                               MessageSource messages) {
        this.stats = stats;
        this.budgets = budgets;
        this.posting = posting;
        this.messages = messages;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(@RequestParam(required = false) String month, Model model) {
        posting.autoPostIfNeeded();
        YearMonth ym = TransactionController.orThisMonth(month);
        var budget = budgets.forMonth(ym);
        MonthSummary s = budget.summary();
        model.addAttribute("month", ym);
        model.addAttribute("summary", s);
        model.addAttribute("kpis", List.of(
                new Kpi("income", s.income(), "income", false),
                new Kpi("outcome", s.expense(), "expense", false),
                new Kpi("remaining", s.remaining(), "primary", false),
                new Kpi("savingRate", s.savingRate(), "investment", true)));
        model.addAttribute("budget", budget.actual());
        model.addAttribute("budgetEstimated", budget.estimated());
        model.addAttribute("budgetBase", budget.base());
        model.addAttribute("upcoming", posting.upcoming(AppTime.today(), UPCOMING_DAYS));
        return "dashboard";
    }

    /** Chart data for one month: 12-month trend, spending by group/category, money flow, daily spending. */
    @GetMapping("/api/dashboard")
    @ResponseBody
    public Map<String, Object> charts(@RequestParam(required = false) String month, Locale locale) {
        YearMonth ym = TransactionController.orThisMonth(month);
        YearMonth from = ym.minusMonths(11);
        List<CatTotal> rows = stats.totals(from, ym);
        List<CatTotal> thisMonth = rows.stream().filter(r -> r.month().equals(ym)).toList();
        MonthSummary s = StatsService.summarize(thisMonth, ym, ym).get(0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", ym.toString());
        data.put("labels", labels(locale));
        data.put("trend", trend(rows, from, ym));
        data.put("groups", groups(thisMonth, locale));
        data.put("categories", categories(thisMonth, locale));
        data.put("sankey", sankey(thisMonth, s, locale));
        data.put("daily", daily(ym));
        return data;
    }

    private Map<String, String> labels(Locale locale) {
        Map<String, String> l = new LinkedHashMap<>();
        for (String key : List.of("income", "outcome", "remaining", "transfer", "leftOver", "shortfall", "noData",
                "spentOn")) {
            l.put(key, msg("chart." + key, locale));
        }
        return l;
    }

    private Map<String, Object> trend(List<CatTotal> rows, YearMonth from, YearMonth to) {
        List<Map<String, Integer>> months = new ArrayList<>();
        List<BigDecimal> income = new ArrayList<>();
        List<BigDecimal> outcome = new ArrayList<>();
        List<BigDecimal> transfer = new ArrayList<>();
        List<BigDecimal> remaining = new ArrayList<>();
        for (MonthSummary m : StatsService.summarize(rows, from, to)) {
            months.add(Map.of("y", m.month().getYear(), "m", m.month().getMonthValue()));
            income.add(m.income());
            outcome.add(m.expense());
            transfer.add(m.transfer());
            remaining.add(m.isEmpty() ? null : m.remaining());
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("months", months);
        t.put("income", income);
        t.put("outcome", outcome);
        t.put("transfer", transfer);
        t.put("remaining", remaining);
        return t;
    }

    /** Expenses per group (inner ring). Groups in their fixed order so colours never move. */
    private List<Map<String, Object>> groups(List<CatTotal> rows, Locale locale) {
        Map<BudgetGroup, BigDecimal> byGroup = new EnumMap<>(BudgetGroup.class);
        rows.stream().filter(r -> r.type() == TxType.EXPENSE && r.category().getBudgetGroup() != null)
                .forEach(r -> byGroup.merge(r.category().getBudgetGroup(), r.amount(), BigDecimal::add));
        List<Map<String, Object>> out = new ArrayList<>();
        byGroup.forEach((g, v) -> {
            if (v.signum() > 0) {
                out.add(Map.of("key", g.name(), "name", msg("group." + g.name(), locale), "value", v));
            }
        });
        return out;
    }

    /** Expenses per category (outer ring), in group order then largest first. */
    private List<Map<String, Object>> categories(List<CatTotal> rows, Locale locale) {
        Map<Category, BigDecimal> byCat = new LinkedHashMap<>();
        rows.stream().filter(r -> r.type() == TxType.EXPENSE && r.category().getBudgetGroup() != null)
                .forEach(r -> byCat.merge(r.category(), r.amount(), BigDecimal::add));
        return byCat.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .sorted(Comparator.<Map.Entry<Category, BigDecimal>, Integer>comparing(
                                e -> e.getKey().getBudgetGroup().ordinal())
                        .thenComparing(Map.Entry::getValue, Comparator.reverseOrder()))
                .map(e -> Map.<String, Object>of("key", e.getKey().getBudgetGroup().name(),
                        "name", e.getKey().name(locale), "value", e.getValue()))
                .toList();
    }

    /** Money flow: income sources → Income → fixed cost, each spending group, saving, investment, left over. */
    private Map<String, Object> sankey(List<CatTotal> rows, MonthSummary s, Locale locale) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        String incomeNode = msg("chart.income", locale);

        Map<Category, BigDecimal> sources = new LinkedHashMap<>();
        rows.stream().filter(r -> r.type() == TxType.INCOME)
                .forEach(r -> sources.merge(r.category(), r.amount(), BigDecimal::add));
        Map<BudgetGroup, BigDecimal> outflow = new EnumMap<>(BudgetGroup.class);
        StatsService.byGroup(rows).forEach((g, v) -> {
            if (v.signum() > 0) {
                outflow.put(g, v);
            }
        });
        if (sources.isEmpty() && outflow.isEmpty()) {
            return Map.of("nodes", nodes, "links", links);
        }

        nodes.add(Map.of("name", incomeNode, "key", "INCOME"));
        sources.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> {
                    String name = e.getKey().name(locale) + " ";  // trailing space keeps it apart from a group name
                    nodes.add(Map.of("name", name, "key", "SOURCE"));
                    links.add(Map.of("source", name, "target", incomeNode, "value", e.getValue()));
                });
        BigDecimal out = outflow.values().stream().reduce(Money.zero(), BigDecimal::add);
        BigDecimal in = sources.values().stream().filter(v -> v.signum() > 0).reduce(Money.zero(), BigDecimal::add);
        if (out.compareTo(in) > 0) {
            String shortfall = msg("chart.shortfall", locale);
            nodes.add(Map.of("name", shortfall, "key", "SHORTFALL"));
            links.add(Map.of("source", shortfall, "target", incomeNode, "value", out.subtract(in)));
        }
        outflow.forEach((g, v) -> {
            String name = msg("group." + g.name(), locale);
            nodes.add(Map.of("name", name, "key", g.name()));
            links.add(Map.of("source", incomeNode, "target", name, "value", v));
        });
        if (in.compareTo(out) > 0) {
            String left = msg("chart.leftOver", locale);
            nodes.add(Map.of("name", left, "key", "LEFT"));
            links.add(Map.of("source", incomeNode, "target", left, "value", in.subtract(out)));
        }
        return Map.of("nodes", nodes, "links", links);
    }

    /** Every day of the month with its spending (0 when nothing), for the calendar heatmap. */
    private Map<String, Object> daily(YearMonth ym) {
        Map<LocalDate, BigDecimal> spent = stats.dailySpending(ym);
        List<List<Object>> days = new ArrayList<>();
        BigDecimal max = Money.zero();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate day = ym.atDay(d);
            BigDecimal v = spent.getOrDefault(day, Money.zero());
            max = max.max(v);
            days.add(List.of(day.toString(), v));
        }
        return Map.of("range", ym.toString(), "days", days, "max", max);
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }
}
