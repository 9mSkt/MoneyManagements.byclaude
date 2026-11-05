package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.BudgetKind;
import com.moneymgmt.domain.BudgetRule;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.BudgetService;
import com.moneymgmt.service.BudgetService.Setting;
import com.moneymgmt.service.PostingService;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Budget planner: actual and goal budgets per month, and the entries that went over. */
@Controller
@RequestMapping("/budget")
public class BudgetController {

    /** One row of the edit form. mode: PERCENT, AMOUNT or NONE. */
    public record Row(BudgetGroup group, String mode, String value) {
    }

    private final BudgetService budgets;
    private final TransactionRepository transactions;
    private final PostingService posting;

    public BudgetController(BudgetService budgets, TransactionRepository transactions, PostingService posting) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.posting = posting;
    }

    @GetMapping
    public String index(@RequestParam(required = false) String month, Model model) {
        posting.autoPostIfNeeded();
        YearMonth ym = TransactionController.orThisMonth(month);
        var budget = budgets.forMonth(ym);
        Set<Long> overIds = budgets.overBudgetIds(ym);
        List<Transaction> over = overIds.isEmpty() ? List.of()
                : transactions.findByDateBetweenOrderByDateAscIdAsc(ym.atDay(1), ym.atEndOfMonth()).stream()
                        .filter(t -> overIds.contains(t.getId())).toList();
        model.addAttribute("month", ym);
        model.addAttribute("budget", budget);
        model.addAttribute("over", over);
        return "budget/index";
    }

    @GetMapping("/edit")
    public String edit(@RequestParam(required = false) String month, @RequestParam BudgetKind kind, Model model) {
        YearMonth ym = TransactionController.orThisMonth(month);
        Map<BudgetGroup, BudgetRule> inForce = budgets.rulesInForce(kind, ym);
        List<Row> rows = BudgetService.GROUPS.stream().map(g -> {
            BudgetRule r = inForce.get(g);
            if (r != null && r.getFixedAmount() != null) {
                return new Row(g, "AMOUNT", r.getFixedAmount().toPlainString());
            }
            if (r != null && r.getPercent() != null) {
                return new Row(g, "PERCENT", r.getPercent().stripTrailingZeros().toPlainString());
            }
            return new Row(g, "NONE", "");
        }).toList();
        return editForm(ym, kind, rows, Map.of(), true, model);
    }

    /** Form fields: mode_EAT=PERCENT, value_EAT=40, ... and scope=later|month. */
    @PostMapping("/edit")
    public String save(@RequestParam String month, @RequestParam BudgetKind kind,
                       @RequestParam(defaultValue = "later") String scope,
                       @RequestParam Map<String, String> params, Model model, RedirectAttributes flash) {
        YearMonth ym = TransactionController.orThisMonth(month);
        Map<BudgetGroup, Setting> settings = new EnumMap<>(BudgetGroup.class);
        Map<String, String> errors = new LinkedHashMap<>();
        List<Row> rows = BudgetService.GROUPS.stream().map(g -> new Row(g,
                params.getOrDefault("mode_" + g, "NONE"), params.getOrDefault("value_" + g, ""))).toList();
        for (Row row : rows) {
            switch (row.mode()) {
                case "PERCENT" -> {
                    BigDecimal p = Money.parse(row.value().replace("%", ""));
                    if (p == null || p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0) {
                        errors.put(row.group().name(), "budget.error.percent");
                    } else {
                        settings.put(row.group(), new Setting(p, null));
                    }
                }
                case "AMOUNT" -> {
                    BigDecimal a = Money.parse(row.value());
                    if (a == null || a.signum() < 0) {
                        errors.put(row.group().name(), "tx.error.amount");
                    } else {
                        settings.put(row.group(), new Setting(null, a));
                    }
                }
                default -> settings.put(row.group(), Setting.NONE);
            }
        }
        boolean laterToo = !"month".equals(scope);
        if (!errors.isEmpty()) {
            return editForm(ym, kind, rows, errors, laterToo, model);
        }
        budgets.save(kind, ym, settings, laterToo);
        flash.addFlashAttribute("flash", laterToo ? "budget.savedLater" : "budget.savedMonth");
        return "redirect:/budget?month=" + ym;
    }

    private String editForm(YearMonth ym, BudgetKind kind, List<Row> rows, Map<String, String> errors,
                            boolean laterToo, Model model) {
        model.addAttribute("month", ym);
        model.addAttribute("kind", kind);
        model.addAttribute("rows", rows);
        model.addAttribute("errors", errors);
        model.addAttribute("laterToo", laterToo);
        model.addAttribute("summary", budgets.forMonth(ym).summary());
        return "budget/edit";
    }
}
