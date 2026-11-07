package com.moneymgmt.web;

import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.repo.RecurringItemRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.PostingService;
import com.moneymgmt.service.TransactionService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Fixed costs (recurring items), installment plans, and what has been posted for a month. */
@Controller
@RequestMapping("/fixed-costs")
public class FixedCostController {

    private final RecurringItemRepository items;
    private final InstallmentRepository plans;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final TransactionService txService;
    private final PostingService posting;

    public FixedCostController(RecurringItemRepository items, InstallmentRepository plans,
                               CategoryRepository categories, TransactionRepository transactions,
                               TransactionService txService, PostingService posting) {
        this.items = items;
        this.plans = plans;
        this.categories = categories;
        this.transactions = transactions;
        this.txService = txService;
        this.posting = posting;
    }

    @GetMapping
    public String index(@RequestParam(required = false) String month, Model model) {
        posting.autoPostIfNeeded();
        YearMonth ym = TransactionController.orThisMonth(month);
        List<RecurringItem> allItems = items.findAllForList();
        List<Installment> allPlans = plans.findAllForList();

        BigDecimal fixedTotal = allItems.stream().filter(RecurringItem::isActive)
                .filter(r -> r.getCategory().getType() == com.moneymgmt.domain.TxType.EXPENSE)
                .map(RecurringItem::getAmount).reduce(Money.zero(), BigDecimal::add);
        List<Installment> running = allPlans.stream().filter(p -> !p.isFinished()).toList();
        BigDecimal planMonthly = running.stream().map(Installment::getMonthlyAmount)
                .reduce(Money.zero(), BigDecimal::add);
        BigDecimal planDebt = running.stream().map(Installment::getRemainingDebt).reduce(Money.zero(), BigDecimal::add);

        model.addAttribute("month", ym);
        model.addAttribute("isThisMonth", ym.equals(AppTime.thisMonth()));
        model.addAttribute("status", posting.status(ym));
        model.addAttribute("items", allItems);
        model.addAttribute("plans", allPlans);
        model.addAttribute("fixedTotal", fixedTotal);
        model.addAttribute("planMonthly", planMonthly);
        model.addAttribute("planDebt", planDebt);
        model.addAttribute("runningCount", running.size());
        return "fixed/index";
    }

    @PostMapping("/post-item/{id}")
    public String postItem(@PathVariable Long id, @RequestParam String month, RedirectAttributes flash) {
        YearMonth ym = TransactionController.orThisMonth(month);
        posting.postItem(id, ym);
        flash.addFlashAttribute("flash", "fixed.posted");
        return "redirect:/fixed-costs?month=" + ym;
    }

    @PostMapping("/post-plan/{id}")
    public String postPlan(@PathVariable Long id, @RequestParam String month, RedirectAttributes flash) {
        YearMonth ym = TransactionController.orThisMonth(month);
        posting.postPlan(id, ym);
        flash.addFlashAttribute("flash", "fixed.posted");
        return "redirect:/fixed-costs?month=" + ym;
    }

    /** Change this month's amount of a posted fixed cost (e.g. the electricity bill). */
    @PostMapping("/posted/{txId}")
    public String adjust(@PathVariable Long txId, @RequestParam String amount, @RequestParam String month,
                         RedirectAttributes flash) {
        BigDecimal value = Money.parse(amount);
        YearMonth ym = TransactionController.orThisMonth(month);
        if (value == null || value.signum() <= 0) {
            flash.addFlashAttribute("flashError", "tx.error.amount");
        } else {
            Transaction t = transactions.findById(txId).orElseThrow(TransactionController::notFound);
            t.setAmount(value);
            txService.save(t);
            flash.addFlashAttribute("flash", "tx.updated");
        }
        return "redirect:/fixed-costs?month=" + ym;
    }

    // ---------- Fixed cost items ----------

    @GetMapping("/items/new")
    public String newItem(Model model) {
        RecurringForm f = new RecurringForm();
        categories.findByCode("OTHER_FIXED").ifPresent(c -> f.setCategoryId(c.getId()));
        return itemForm(f, Map.of(), model);
    }

    @GetMapping("/items/{id}/edit")
    public String editItem(@PathVariable Long id, Model model) {
        RecurringItem r = items.findWithCategoryById(id).orElseThrow(TransactionController::notFound);
        return itemForm(RecurringForm.of(r), Map.of(), model);
    }

    @PostMapping("/items")
    public String saveItem(@ModelAttribute("form") RecurringForm f, BindingResult binding, Model model,
                           RedirectAttributes flash) {
        Map<String, String> errors = withBindingErrors(binding, f.validate());
        Category category = f.getCategoryId() == null ? null : categories.findById(f.getCategoryId()).orElse(null);
        if (category == null) {
            errors.putIfAbsent("categoryId", "tx.error.category");
        }
        if (!errors.isEmpty()) {
            return itemForm(f, errors, model);
        }
        RecurringItem r = f.getId() == null ? new RecurringItem(f.getName().trim(), Money.parse(f.getAmount()), category)
                : items.findById(f.getId()).orElseThrow(TransactionController::notFound);
        r.setName(f.getName().trim());
        r.setAmount(Money.parse(f.getAmount()));
        r.setCategory(category);
        r.setDayOfMonth(f.getDayOfMonth());
        r.setActive(f.isActive());
        items.save(r);
        posting.recheck();
        flash.addFlashAttribute("flash", "fixed.saved");
        return "redirect:/fixed-costs";
    }

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@PathVariable Long id, RedirectAttributes flash) {
        items.deleteById(id);
        flash.addFlashAttribute("flash", "fixed.deleted");
        return "redirect:/fixed-costs";
    }

    private String itemForm(RecurringForm f, Map<String, String> errors, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        return "fixed/item-form";
    }

    // ---------- Installment plans ----------

    @GetMapping("/installments/new")
    public String newPlan(Model model) {
        InstallmentForm f = InstallmentForm.blank();
        categories.findByCode("SHOPPING").ifPresent(c -> f.setCategoryId(c.getId()));
        return planForm(f, Map.of(), null, model);
    }

    @GetMapping("/installments/{id}/edit")
    public String editPlan(@PathVariable Long id, Model model) {
        Installment p = plans.findWithCategoryById(id).orElseThrow(TransactionController::notFound);
        return planForm(InstallmentForm.of(p), Map.of(), p, model);
    }

    @PostMapping("/installments")
    public String savePlan(@ModelAttribute("form") InstallmentForm f, BindingResult binding, Model model,
                           RedirectAttributes flash) {
        Map<String, String> errors = withBindingErrors(binding, binding.hasErrors() ? new LinkedHashMap<>() : f.validate());
        Category category = f.getCategoryId() == null ? null : categories.findById(f.getCategoryId()).orElse(null);
        if (category == null) {
            errors.putIfAbsent("categoryId", "tx.error.category");
        }
        Installment existing = f.getId() == null ? null
                : plans.findWithCategoryById(f.getId()).orElseThrow(TransactionController::notFound);
        if (!errors.isEmpty()) {
            return planForm(f, errors, existing, model);
        }
        var start = AppTime.parseMonth(f.getStartMonth()).atDay(1);
        Installment p = existing != null ? existing
                : new Installment(f.getItem().trim(), Money.parse(f.getFullPrice()), f.resolvedMonthly(),
                        f.getMonths(), start);
        p.setItem(f.getItem().trim());
        p.setFullPrice(Money.parse(f.getFullPrice()));
        p.setMonths(f.getMonths());
        p.setInterestPercent(f.rate());
        p.setMonthlyAmount(f.resolvedMonthly());
        p.setStartMonth(start);
        p.setPaidCount(f.getPaidCount());
        p.setCategory(category);
        plans.save(p);
        posting.recheck();
        flash.addFlashAttribute("flash", "inst.saved");
        return "redirect:/fixed-costs";
    }

    @PostMapping("/installments/{id}/delete")
    public String deletePlan(@PathVariable Long id, RedirectAttributes flash) {
        plans.deleteById(id);
        flash.addFlashAttribute("flash", "inst.deleted");
        return "redirect:/fixed-costs";
    }

    /** Change the number of months; payments already in the app get the new monthly amount. */
    @PostMapping("/installments/{id}/revise")
    public String revisePlan(@PathVariable Long id, @RequestParam(defaultValue = "0") int months, RedirectAttributes flash) {
        Installment p = plans.findById(id).orElseThrow(TransactionController::notFound);
        if (months < 1 || months > 120) {
            flash.addFlashAttribute("flashError", "inst.error.months");
            return "redirect:/fixed-costs/installments/" + id + "/edit";
        }
        if (months != p.getMonths()) {
            txService.revisePlan(id, months);
            posting.recheck();
        }
        flash.addFlashAttribute("flash", "inst.revised");
        return "redirect:/fixed-costs/installments/" + id + "/edit";
    }

    /** Cancel the plan: back to one purchase at the full price; the other payments are removed. */
    @PostMapping("/installments/{id}/cancel")
    public String cancelPlan(@PathVariable Long id, RedirectAttributes flash) {
        plans.findById(id).orElseThrow(TransactionController::notFound);
        Transaction kept = txService.cancelPlan(id);
        flash.addFlashAttribute("flash", "inst.cancelled");
        return kept == null ? "redirect:/fixed-costs"
                : "redirect:/transactions?month=" + YearMonth.from(kept.getDate());
    }

    private String planForm(InstallmentForm f, Map<String, String> errors, Installment existing, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        model.addAttribute("existing", existing);
        model.addAttribute("schedule", existing == null ? List.of() : schedule(existing));
        return "fixed/installment-form";
    }

    /**
     * One row per payment. status: paid (an entry exists), counted (counted as paid but no entry in the app,
     * e.g. paid before the app), due (this month or earlier, not paid yet), upcoming.
     */
    public record ScheduleRow(int no, java.time.LocalDate month, BigDecimal amount, Transaction entry, String status) {
    }

    private List<ScheduleRow> schedule(Installment plan) {
        List<Transaction> paid = transactions.findByInstallmentOrderByDateAscIdAsc(plan);
        java.time.LocalDate thisMonth = AppTime.thisMonth().atDay(1);
        List<ScheduleRow> rows = new java.util.ArrayList<>();
        for (int no = 1; no <= plan.getMonths(); no++) {
            java.time.LocalDate month = plan.monthOfPayment(no);
            final int n = no;
            Transaction entry = paid.stream().filter(t -> t.getInstallmentNo() != null && t.getInstallmentNo() == n)
                    .findFirst()
                    .orElseGet(() -> paid.stream().filter(t -> t.getInstallmentNo() == null
                            && java.time.YearMonth.from(t.getDate()).atDay(1).equals(month)).findFirst().orElse(null));
            String status;
            if (entry != null) {
                status = "paid";
            } else if (no <= plan.getPaidCount()) {
                status = "counted";
            } else if (!month.isAfter(thisMonth)) {
                status = "due";
            } else {
                status = "upcoming";
            }
            rows.add(new ScheduleRow(no, month, entry != null ? entry.getAmount() : plan.getMonthlyAmount(), entry, status));
        }
        return rows;
    }

    /** Fields the browser sent in a wrong format (e.g. letters in "months") become form errors. */
    static Map<String, String> withBindingErrors(BindingResult binding, Map<String, String> errors) {
        Map<String, String> all = new LinkedHashMap<>();
        binding.getFieldErrors().forEach(e -> all.putIfAbsent(e.getField(), "form.error.invalid"));
        errors.forEach(all::putIfAbsent);
        return all;
    }
}
