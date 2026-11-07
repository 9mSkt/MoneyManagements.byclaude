package com.moneymgmt.web;

import com.moneymgmt.domain.Account;
import com.moneymgmt.domain.AccountType;
import com.moneymgmt.repo.AccountBalanceRepository;
import com.moneymgmt.repo.AccountRepository;
import com.moneymgmt.service.NetWorthService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Accounts (wallet, bank, investment cash, credit card) with balances typed in by the owner, and net worth. */
@Controller
public class AccountController {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final NetWorthService netWorth;
    private final MessageSource messages;

    public AccountController(AccountRepository accounts, AccountBalanceRepository balances, NetWorthService netWorth,
                             MessageSource messages) {
        this.accounts = accounts;
        this.balances = balances;
        this.netWorth = netWorth;
        this.messages = messages;
    }

    @GetMapping("/accounts")
    public String index(Model model) {
        model.addAttribute("nw", netWorth.now());
        return "accounts/index";
    }

    /** Net worth at the end of each of the last 12 months. */
    @GetMapping("/api/accounts/history")
    @ResponseBody
    public Map<String, Object> history(Locale locale) {
        List<Map<String, Integer>> months = new ArrayList<>();
        List<BigDecimal> cash = new ArrayList<>();
        List<BigDecimal> invest = new ArrayList<>();
        List<BigDecimal> debts = new ArrayList<>();
        List<BigDecimal> total = new ArrayList<>();
        for (var p : netWorth.history(12)) {
            months.add(Map.of("y", p.month().getYear(), "m", p.month().getMonthValue()));
            cash.add(p.cash());
            invest.add(p.portfolio());
            debts.add(p.debts().negate());
            total.add(p.total());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", Map.of("cash", msg("acc.chart.cash", locale), "invest", msg("acc.chart.invest", locale),
                "debts", msg("acc.chart.debts", locale), "total", msg("acc.chart.total", locale),
                "noData", msg("acc.chart.noData", locale)));
        data.put("months", months);
        data.put("cash", cash);
        data.put("invest", invest);
        data.put("debts", debts);
        data.put("total", total);
        return data;
    }

    /** Update the balance of every active account at once, for one date. */
    @PostMapping("/accounts/balances")
    @Transactional
    public String updateBalances(@RequestParam Map<String, String> params, RedirectAttributes flash) {
        LocalDate date = parseDate(params.get("date"));
        if (date == null || date.isAfter(AppTime.today())) {
            flash.addFlashAttribute("flashError", "acc.error.date");
            return "redirect:/accounts";
        }
        int saved = 0;
        for (Account a : accounts.findAll()) {
            String text = params.get("balance_" + a.getId());
            if (text == null || text.isBlank()) {
                continue;
            }
            BigDecimal value = Money.parse(text);
            if (value == null) {
                flash.addFlashAttribute("flashError", "acc.error.balance");
                return "redirect:/accounts";
            }
            netWorth.recordBalance(a, date, value);
            saved++;
        }
        flash.addFlashAttribute("flash", saved > 0 ? "acc.balancesSaved" : "acc.nothingChanged");
        return "redirect:/accounts";
    }

    @GetMapping("/accounts/new")
    public String newAccount(Model model) {
        return form(new AccountForm(), Map.of(), null, model);
    }

    @GetMapping("/accounts/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Account a = accounts.findById(id).orElseThrow(TransactionController::notFound);
        return form(AccountForm.of(a), Map.of(), a, model);
    }

    @PostMapping("/accounts")
    @Transactional
    public String save(@ModelAttribute("form") AccountForm f, BindingResult binding, Model model,
                       RedirectAttributes flash) {
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        Account existing = f.getId() == null ? null : accounts.findById(f.getId()).orElseThrow(TransactionController::notFound);
        if (!errors.isEmpty()) {
            return form(f, errors, existing, model);
        }
        Account a = existing;
        if (a == null) {
            BigDecimal opening = Money.parse(f.getBalance());
            a = accounts.save(new Account(f.getName().trim(), f.getType(), opening));
            netWorth.recordBalance(a, AppTime.today(), opening);
        }
        a.setName(f.getName().trim());
        a.setType(f.getType());
        a.setNote(f.cleanNote());
        a.setActive(f.isActive());
        accounts.save(a);
        flash.addFlashAttribute("flash", "acc.saved");
        return "redirect:/accounts";
    }

    @PostMapping("/accounts/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        // Transactions keep their entries; their account link is cleared first (no cascade on that column).
        accounts.findById(id).ifPresent(a -> {
            accounts.clearTransactionAccount(a.getId());
            accounts.clearRecurringAccount(a.getId());
            accounts.delete(a);
        });
        flash.addFlashAttribute("flash", "acc.deleted");
        return "redirect:/accounts";
    }

    @PostMapping("/accounts/balance/{balanceId}/delete")
    public String deleteBalance(@PathVariable Long balanceId, RedirectAttributes flash) {
        Long accountId = balances.findById(balanceId).map(b -> b.getAccount().getId()).orElse(null);
        balances.deleteById(balanceId);
        flash.addFlashAttribute("flash", "acc.balanceDeleted");
        return accountId == null ? "redirect:/accounts" : "redirect:/accounts/" + accountId + "/edit";
    }

    private String form(AccountForm f, Map<String, String> errors, Account existing, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        model.addAttribute("types", AccountType.values());
        model.addAttribute("history", existing == null ? List.of() : balances.findByAccountOrderByDateDescIdDesc(existing));
        return "accounts/form";
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }
}
