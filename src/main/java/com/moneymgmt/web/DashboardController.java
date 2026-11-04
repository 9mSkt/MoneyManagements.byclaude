package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Phase 1 look-and-feel mockup. Every number here is MADE-UP sample data, shown with a "sample data" badge.
 * Phase 3 replaces it with real totals computed from transactions.
 */
@Controller
public class DashboardController {

    public record Kpi(String key, BigDecimal value, String kind, boolean percent) {
    }

    public record BudgetBar(BudgetGroup group, BigDecimal budget, BigDecimal spent, BigDecimal percent) {
        /** ok below 80 %, warn from 80 % to 100 %, over above 100 %. */
        public String level() {
            if (percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                return "over";
            }
            return percent.compareTo(BigDecimal.valueOf(80)) >= 0 ? "warn" : "ok";
        }

        /** Bar width, capped at 100. */
        public BigDecimal width() {
            return percent.min(BigDecimal.valueOf(100));
        }
    }

    public record Upcoming(String labelKey, String detail, LocalDate due, BigDecimal amount) {
    }

    private final MessageSource messages;

    public DashboardController(MessageSource messages) {
        this.messages = messages;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        BigDecimal income = Money.of("52000");
        BigDecimal outcome = Money.of("38450");
        BigDecimal remaining = income.subtract(outcome);
        model.addAttribute("kpis", List.of(
                new Kpi("income", income, "income", false),
                new Kpi("outcome", outcome, "expense", false),
                new Kpi("remaining", remaining, "primary", false),
                new Kpi("savingRate", Money.percent(remaining, income), "investment", true)));
        model.addAttribute("budgetBars", List.of(
                bar(BudgetGroup.EAT, "10000", "8700"),
                bar(BudgetGroup.SHOPPING, "10000", "10850"),
                bar(BudgetGroup.GAS, "5000", "2900")));
        model.addAttribute("upcoming", List.of(
                new Upcoming("upcoming.installment", "Phone 3/10", LocalDate.of(2026, 12, 1), Money.of("1250")),
                new Upcoming("upcoming.fixed", "Car", LocalDate.of(2026, 12, 5), Money.of("9000")),
                new Upcoming("upcoming.fixed", "Electricity", LocalDate.of(2026, 12, 10), Money.of("1400")),
                new Upcoming("upcoming.installment", "Laptop 7/12", LocalDate.of(2026, 12, 15), Money.of("2100"))));
        model.addAttribute("monthLabel", LocalDate.of(2026, 11, 1));
        return "dashboard";
    }

    /** Chart data for the mockup, fetched as a small JSON call (the same shape Phase 3 will serve). */
    @GetMapping("/api/dashboard/sample")
    @ResponseBody
    public Map<String, Object> sampleCharts(Locale locale) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("months", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
        data.put("year", 2026);
        data.put("income", amounts(48000, 49500, 51000, 48200, 50400, 53000, 49800, 50100, 51500, 52500, 52000, 0));
        data.put("outcome", amounts(36200, 41000, 37800, 35900, 39400, 44100, 38800, 37200, 40300, 39000, 38450, 0));
        Map<String, Object> donut = new LinkedHashMap<>();
        donut.put(group(BudgetGroup.FIXED, locale), Money.of("21000"));
        donut.put(group(BudgetGroup.EAT, locale), Money.of("8700"));
        donut.put(group(BudgetGroup.SHOPPING, locale), Money.of("10850"));
        donut.put(group(BudgetGroup.GAS, locale), Money.of("2900"));
        donut.put(group(BudgetGroup.NECESSARY, locale), Money.of("1500"));
        data.put("byGroup", donut.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "value", e.getValue())).toList());
        data.put("labels", Map.of(
                "income", msg("kpi.income", locale),
                "outcome", msg("kpi.outcome", locale),
                "remaining", msg("kpi.remaining", locale)));
        return data;
    }

    private static BudgetBar bar(BudgetGroup group, String budget, String spent) {
        BigDecimal b = Money.of(budget);
        BigDecimal s = Money.of(spent);
        return new BudgetBar(group, b, s, Money.percent(s, b));
    }

    private static List<BigDecimal> amounts(long... values) {
        return java.util.Arrays.stream(values).mapToObj(Money::of).toList();
    }

    private String group(BudgetGroup g, Locale locale) {
        return msg("group." + g.name(), locale);
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }
}
