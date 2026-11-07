package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.IncentiveService;
import com.moneymgmt.service.PostingService;
import com.moneymgmt.service.TransactionService;
import com.moneymgmt.util.AppTime;
import jakarta.servlet.http.HttpServletRequest;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final PostingService posting;
    private final IncentiveService incentives;
    private final com.moneymgmt.service.BudgetService budgets;
    private final com.moneymgmt.repo.InstallmentRepository plans;

    /**
     * One card of the "By group" view: every entry of one budget group (one colour), e.g. Fixed cost holds rent,
     * car payment, Mom and the bills; income is one card. Total, share of all entries of the same type, and the
     * entries (newest first).
     */
    public record GroupCard(String key, BudgetGroup group, TxType type, java.math.BigDecimal total,
                            java.math.BigDecimal share, List<Transaction> rows, int over) {
        /** Entries shown before "Show more". */
        public static final int VISIBLE = 5;

        public List<Transaction> first() {
            return rows.subList(0, Math.min(VISIBLE, rows.size()));
        }

        public List<Transaction> rest() {
            return rows.size() <= VISIBLE ? List.of() : rows.subList(VISIBLE, rows.size());
        }

        /** Cards ordered: income, then the budget groups in their usual order (fixed, gas, ... saving, investment). */
        static List<GroupCard> of(List<Transaction> rows, java.util.Set<Long> overIds) {
            Map<String, List<Transaction>> byKey = new LinkedHashMap<>();
            Map<TxType, java.math.BigDecimal> typeTotals = new java.util.EnumMap<>(TxType.class);
            for (Transaction t : rows) {
                BudgetGroup g = t.getCategory().getBudgetGroup();
                byKey.computeIfAbsent(g != null ? g.name() : t.getType().name(), k -> new java.util.ArrayList<>()).add(t);
                typeTotals.merge(t.getType(), t.getAmount(), java.math.BigDecimal::add);
            }
            List<GroupCard> out = new java.util.ArrayList<>();
            byKey.forEach((key, list) -> {
                TxType type = list.get(0).getType();
                BudgetGroup group = list.get(0).getCategory().getBudgetGroup();
                java.math.BigDecimal total = list.stream().map(Transaction::getAmount)
                        .reduce(com.moneymgmt.util.Money.zero(), java.math.BigDecimal::add);
                java.math.BigDecimal share = com.moneymgmt.util.Money.percent(total, typeTotals.get(type))
                        .max(java.math.BigDecimal.ZERO).min(java.math.BigDecimal.valueOf(100));
                int over = (int) list.stream().filter(t -> overIds.contains(t.getId())).count();
                out.add(new GroupCard(key, group, type, total, share, list, over));
            });
            out.sort(java.util.Comparator.comparingInt(GroupCard::order));
            return out;
        }

        private int order() {
            if (group != null) {
                return 1 + group.ordinal();
            }
            return type == TxType.INCOME ? 0 : 100 + type.ordinal();
        }
    }

    /** An installment plan as seen in one month: which payment falls in it. */
    public record MonthPlan(com.moneymgmt.domain.Installment plan, int no) {
        public java.math.BigDecimal progress() {
            return com.moneymgmt.util.Money.percent(java.math.BigDecimal.valueOf(no),
                    java.math.BigDecimal.valueOf(plan.getMonths()));
        }
    }

    public TransactionController(TransactionService service, TransactionRepository transactions,
                                 CategoryRepository categories, PostingService posting, IncentiveService incentives,
                                 com.moneymgmt.service.BudgetService budgets,
                                 com.moneymgmt.repo.InstallmentRepository plans) {
        this.service = service;
        this.transactions = transactions;
        this.categories = categories;
        this.posting = posting;
        this.incentives = incentives;
        this.budgets = budgets;
        this.plans = plans;
    }

    /** List with filters. An HTMX request (filter change) gets only the results part of the page. */
    @GetMapping
    public String list(@RequestParam(required = false) String month,
                       @RequestParam(required = false) TxType type,
                       @RequestParam(required = false) BudgetGroup group,
                       @RequestParam(required = false) Long category,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String view,
                       HttpServletRequest request, Model model) {
        posting.autoPostIfNeeded();
        boolean allMonths = "all".equals(month);
        YearMonth ym = allMonths ? null : orThisMonth(month);
        var filter = new TransactionService.Filter(ym, type, group, category, TxForm.trimToNull(q));
        var result = service.search(filter);

        model.addAttribute("filter", filter);
        model.addAttribute("allMonths", allMonths);
        model.addAttribute("month", ym == null ? AppTime.thisMonth() : ym);
        model.addAttribute("result", result);
        model.addAttribute("groups", BudgetGroup.values());
        model.addAttribute("types", TxType.values());
        java.util.Set<Long> overIds = ym == null ? java.util.Set.of() : budgets.overBudgetIds(ym);
        model.addAttribute("overIds", overIds);
        boolean byDate = "date".equals(view);
        model.addAttribute("byDate", byDate);
        model.addAttribute("cards", byDate ? List.of() : GroupCard.of(result.rows(), overIds));
        model.addAttribute("filterQuery", filterQuery(filter) + (byDate ? "&view=date" : ""));
        model.addAttribute("baseQuery", "?month=" + (allMonths ? "all" : ym) + filterQuery(filter));
        // Entries that pushed a group over this month's actual budget are shown in red.
        if (ym != null) {
            model.addAttribute("incentive", incentives.forMonth(ym));
            // Installment plans with a payment in this month (shown above the list).
            model.addAttribute("monthPlans", plans.findAllForList().stream().filter(p -> p.hasPaymentIn(ym))
                    .map(p -> new MonthPlan(p, p.paymentNoIn(ym))).toList());
        }
        boolean hx = request.getHeader("HX-Request") != null;
        model.addAttribute("hx", hx);
        if (hx) {
            return "transactions/list :: results";
        }
        return "transactions/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) TxType type, @RequestParam(required = false) String month,
                          Model model) {
        TxForm f = new TxForm();
        if (type != null) {
            f.setType(type);
        }
        YearMonth ym = AppTime.parseMonth(month);
        f.setDate(ym == null || ym.equals(AppTime.thisMonth()) ? AppTime.today() : ym.atDay(1));
        return form(f, Map.of(), null, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Transaction t = transactions.findWithDetailsById(id).orElseThrow(TransactionController::notFound);
        return form(TxForm.of(t), Map.of(), t, model);
    }

    /** Saves a new or edited transaction (also used by the quick-add drawer). */
    @PostMapping
    public String save(@ModelAttribute TxForm f, org.springframework.validation.BindingResult binding, Model model,
                       RedirectAttributes flash) {
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, new LinkedHashMap<>());
        var amount = f.parsedAmount();
        if (amount == null) {
            errors.put("amount", "tx.error.amount");
        } else if (amount.signum() == 0) {
            errors.put("amount", "tx.error.amountZero");
        }
        Category category = f.getCategoryId() == null ? null : categories.findById(f.getCategoryId()).orElse(null);
        if (category == null) {
            errors.put("categoryId", "tx.error.category");
        }
        if (f.getDate() == null) {
            errors.put("date", "tx.error.date");
        }
        Transaction existing = null;
        if (f.getId() != null) {
            existing = transactions.findWithDetailsById(f.getId()).orElseThrow(TransactionController::notFound);
        }
        // "1/3" starts an installment plan from this payment (expenses only, and not if already linked to one).
        int[] period = null;
        boolean canStartPlan = category != null && category.getType() == TxType.EXPENSE
                && (existing == null || existing.getInstallment() == null);
        if (canStartPlan && TxForm.trimToNull(f.getPeriod()) != null) {
            period = TxForm.parsePeriod(f.getPeriod());
            if (period == null) {
                errors.put("period", "tx.error.period");
            } else if (amount != null && amount.signum() < 0) {
                errors.put("amount", "tx.error.amount");
            }
        }
        // Credit card fee / interest: only for installment payments (a new 1/X entry or a payment of a plan).
        boolean installmentEntry = period != null || (existing != null && existing.getInstallment() != null);
        java.math.BigDecimal fee = null;
        if (installmentEntry && TxForm.trimToNull(f.getFee()) != null) {
            fee = com.moneymgmt.util.Money.parse(f.getFee());
            if (fee == null || fee.signum() < 0) {
                errors.put("fee", "tx.error.fee");
            }
        }
        if (!errors.isEmpty()) {
            return form(f, errors, existing, model);
        }

        Transaction t = existing != null ? existing
                : new Transaction(f.getDate(), category.getType(), amount, category, null);
        t.setDate(f.getDate());
        t.setType(category.getType());
        t.setAmount(amount);
        t.setCategory(category);
        t.setDescription(TxForm.trimToNull(f.getDescription()));
        t.setNote(TxForm.trimToNull(f.getNote()));
        t.setTags(TxForm.cleanTags(f.getTags()));
        if (period != null) {
            // The amount typed with a period is the full price; the app works out the monthly payment.
            service.saveAsInstallment(t, amount, period[0], period[1], fee);
            posting.recheck();
            flash.addFlashAttribute("flash", "tx.savedInstallment");
            return "redirect:" + back(f.getReturnTo(), YearMonth.from(t.getDate()));
        }
        if (installmentEntry) {
            // A payment of a plan: the amount is the payment itself, the fee is added on top.
            boolean hasFee = fee != null && fee.signum() > 0;
            t.setFee(hasFee ? com.moneymgmt.util.Money.scale(fee) : null);
            t.setAmount(hasFee ? amount.add(fee) : amount);
        }
        service.save(t);

        flash.addFlashAttribute("flash", existing != null ? "tx.updated" : "tx.saved");
        return "redirect:" + back(f.getReturnTo(), YearMonth.from(t.getDate()));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam(required = false) String returnTo,
                         RedirectAttributes flash) {
        Transaction t = transactions.findById(id).orElseThrow(TransactionController::notFound);
        YearMonth month = YearMonth.from(t.getDate());
        service.delete(id);
        flash.addFlashAttribute("flash", "tx.deleted");
        return "redirect:" + back(returnTo, month);
    }

    // ---------- Pay in installments (credit card 0 % campaign) ----------

    /** A month's purchases that can be turned into 0 % installment plans, with checkboxes. */
    @GetMapping("/installments")
    public String splitForm(@RequestParam(required = false) String month, Model model) {
        YearMonth ym = orThisMonth(month);
        List<Transaction> rows = transactions.findByDateBetweenOrderByDateAscIdAsc(ym.atDay(1), ym.atEndOfMonth())
                .stream().filter(TransactionService::canSplit).toList();
        model.addAttribute("month", ym);
        model.addAttribute("rows", rows);
        model.addAttribute("started", plans.findAllForList().stream()
                .filter(p -> YearMonth.from(p.getStartMonth()).equals(ym)).toList());
        return "transactions/installments";
    }

    @PostMapping("/installments")
    public String split(@RequestParam(required = false) List<Long> ids, @RequestParam(defaultValue = "3") int months,
                        @RequestParam(required = false) String month, RedirectAttributes flash) {
        YearMonth ym = orThisMonth(month);
        if (ids == null || ids.isEmpty()) {
            flash.addFlashAttribute("flashError", "split.error.none");
            return "redirect:/transactions/installments?month=" + ym;
        }
        if (months < 2 || months > 60) {
            flash.addFlashAttribute("flashError", "split.error.months");
            return "redirect:/transactions/installments?month=" + ym;
        }
        int done = service.splitIntoInstallments(ids, months);
        posting.recheck();
        flash.addFlashAttribute(done > 0 ? "flash" : "flashError", done > 0 ? "split.done" : "split.error.none");
        return "redirect:/transactions?month=" + ym;
    }

    /** Cancel several plans at once: each goes back to one purchase at the full price. */
    @PostMapping("/installments/cancel")
    public String cancelPlans(@RequestParam(required = false) List<Long> planIds,
                              @RequestParam(required = false) String month, RedirectAttributes flash) {
        YearMonth ym = orThisMonth(month);
        if (planIds == null || planIds.isEmpty()) {
            flash.addFlashAttribute("flashError", "split.error.noneCancel");
            return "redirect:/transactions/installments?month=" + ym;
        }
        for (Long id : planIds) {
            if (plans.existsById(id)) {
                service.cancelPlan(id);
            }
        }
        posting.recheck();
        flash.addFlashAttribute("flash", "split.cancelled");
        return "redirect:/transactions/installments?month=" + ym;
    }

    // ---------- Monthly incentive ----------

    @GetMapping("/incentive")
    public String incentive(@RequestParam(required = false) String month, Model model) {
        YearMonth ym = orThisMonth(month);
        model.addAttribute("form", IncentiveForm.of(ym, incentives.forMonth(ym)));
        model.addAttribute("errors", Map.of());
        model.addAttribute("history", incentives.history(12));
        return "transactions/incentive";
    }

    @PostMapping("/incentive")
    public String saveIncentive(@ModelAttribute("form") IncentiveForm f, org.springframework.validation.BindingResult binding,
                                Model model, RedirectAttributes flash) {
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            model.addAttribute("history", incentives.history(12));
            return "transactions/incentive";
        }
        incentives.save(f.toInput());
        flash.addFlashAttribute("flash", "incentive.saved");
        return "redirect:/transactions?month=" + f.yearMonth();
    }

    // ---------------------------------------------------------------------------------------------

    private String form(TxForm f, Map<String, String> errors, Transaction existing, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        model.addAttribute("existing", existing);
        return "transactions/form";
    }

    /** The non-month filters as "&type=…&q=…", so month links keep them. */
    static String filterQuery(TransactionService.Filter f) {
        StringBuilder sb = new StringBuilder();
        if (f.type() != null) {
            sb.append("&type=").append(f.type());
        }
        if (f.group() != null) {
            sb.append("&group=").append(f.group());
        }
        if (f.categoryId() != null) {
            sb.append("&category=").append(f.categoryId());
        }
        if (f.q() != null) {
            sb.append("&q=").append(java.net.URLEncoder.encode(f.q(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static YearMonth orThisMonth(String month) {
        YearMonth ym = AppTime.parseMonth(month);
        return ym == null ? AppTime.thisMonth() : ym;
    }

    /** Where to go after saving: the page the form was opened from (same site only), else that month's list. */
    static String back(String returnTo, YearMonth month) {
        boolean safe = returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//")
                && !returnTo.contains("\\") && !returnTo.contains("..") && returnTo.length() < 200
                && !returnTo.startsWith("/transactions") && !returnTo.startsWith("/login");
        if (safe && !returnTo.contains("?")) {
            return returnTo;
        }
        return "/transactions?month=" + month;
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
