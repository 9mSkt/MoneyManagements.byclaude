package com.moneymgmt.web;

import com.moneymgmt.domain.Goal;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.GoalRepository;
import com.moneymgmt.service.TransactionService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Saving goals: target, saved so far, deadline and a progress bar. Typed in by the owner. */
@Controller
@RequestMapping("/goals")
public class GoalController {

    private final GoalRepository goals;
    private final CategoryRepository categories;
    private final TransactionService txService;

    public GoalController(GoalRepository goals, CategoryRepository categories, TransactionService txService) {
        this.goals = goals;
        this.categories = categories;
        this.txService = txService;
    }

    @GetMapping
    public String index(Model model) {
        List<Goal> all = goals.findAllForList();
        BigDecimal target = all.stream().map(Goal::getTargetAmount).reduce(Money.zero(), BigDecimal::add);
        BigDecimal saved = all.stream().map(Goal::getSavedAmount).reduce(Money.zero(), BigDecimal::add);
        var today = AppTime.today();
        BigDecimal monthly = all.stream().map(g -> g.monthlyNeeded(today)).filter(java.util.Objects::nonNull)
                .reduce(Money.zero(), BigDecimal::add);
        model.addAttribute("goals", all);
        model.addAttribute("totalTarget", target);
        model.addAttribute("totalSaved", saved);
        model.addAttribute("totalPercent", Money.percent(saved, target).min(BigDecimal.valueOf(100)));
        model.addAttribute("monthlyNeeded", monthly);
        return "goals/index";
    }

    @GetMapping("/new")
    public String newGoal(Model model) {
        return form(new GoalForm(), Map.of(), model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Goal g = goals.findById(id).orElseThrow(TransactionController::notFound);
        return form(GoalForm.of(g), Map.of(), model);
    }

    @PostMapping
    public String save(@ModelAttribute("form") GoalForm f, BindingResult binding, Model model, RedirectAttributes flash) {
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        if (!errors.isEmpty()) {
            return form(f, errors, model);
        }
        Goal g = f.getId() == null
                ? new Goal(f.getName().trim(), f.targetAmount(), f.savedAmount(), f.parsedDeadline())
                : goals.findById(f.getId()).orElseThrow(TransactionController::notFound);
        g.setName(f.getName().trim());
        g.setTargetAmount(f.targetAmount());
        g.setSavedAmount(f.savedAmount());
        g.setDeadline(f.parsedDeadline());
        g.setNote(f.cleanNote());
        goals.save(g);
        flash.addFlashAttribute("flash", "goal.flash.saved");
        return "redirect:/goals";
    }

    /**
     * Put money into a goal (or take it out). Optionally also records a saving transfer (Deposit) in
     * Transactions, so it counts toward the month's saving budget.
     */
    @PostMapping("/{id}/money")
    @Transactional
    public String money(@PathVariable Long id, @RequestParam String amount,
                        @RequestParam(defaultValue = "in") String direction,
                        @RequestParam(defaultValue = "false") boolean record, RedirectAttributes flash) {
        Goal g = goals.findById(id).orElseThrow(TransactionController::notFound);
        BigDecimal value = Money.parse(amount);
        if (value == null || value.signum() <= 0) {
            flash.addFlashAttribute("flashError", "tx.error.amount");
            return "redirect:/goals";
        }
        boolean out = "out".equals(direction);
        BigDecimal saved = out ? g.getSavedAmount().subtract(value) : g.getSavedAmount().add(value);
        g.setSavedAmount(Money.scale(saved.max(BigDecimal.ZERO)));
        goals.save(g);
        if (record && !out) {
            categories.findByCode("DEPOSIT").ifPresent(c -> {
                Transaction t = new Transaction(AppTime.today(), TxType.TRANSFER, value, c, g.getName());
                txService.save(t);
            });
        }
        flash.addFlashAttribute("flash", out ? "goal.takenOut" : "goal.added");
        return "redirect:/goals";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        goals.deleteById(id);
        flash.addFlashAttribute("flash", "goal.deleted");
        return "redirect:/goals";
    }

    private String form(GoalForm f, Map<String, String> errors, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        return "goals/form";
    }
}
