package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Account;
import com.moneymgmt.domain.AccountType;
import com.moneymgmt.domain.Goal;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.AccountBalanceRepository;
import com.moneymgmt.repo.AccountRepository;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.GoalRepository;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.NetWorthService;
import com.moneymgmt.service.ReportService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Goals, accounts and net worth, reports and downloads, categories. Made-up data, rolled back after each test. */
@WithMockUser(username = "tester")
@Transactional
class Phase6PagesTest extends AbstractWebTest {

    @Autowired GoalRepository goals;
    @Autowired AccountRepository accounts;
    @Autowired AccountBalanceRepository balances;
    @Autowired InstallmentRepository installments;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    @Autowired NetWorthService netWorth;
    @Autowired ReportService reports;

    private Transaction tx(LocalDate date, String code, String amount, String description) {
        var c = categories.findByCode(code).orElseThrow();
        return transactions.save(new Transaction(date, c.getType(), Money.of(amount), c, description));
    }

    // ---------------------------------------------------------------- goals

    @Test
    void goalCanBeAddedFilledAndShowsProgress() throws Exception {
        LocalDate deadline = AppTime.today().plusMonths(3).withDayOfMonth(1);
        mvc.perform(post("/goals").with(csrf()).param("name", "Sample trip").param("target", "30,000")
                        .param("saved", "6000").param("deadline", deadline.toString()))
                .andExpect(redirectedUrl("/goals"));
        Goal g = goals.findAll().stream().filter(x -> x.getName().equals("Sample trip")).findFirst().orElseThrow();
        assertThat(g.getProgressPercent()).isEqualByComparingTo("20");
        assertThat(g.monthsLeft(AppTime.today())).isEqualTo(4);
        assertThat(g.monthlyNeeded(AppTime.today())).isEqualByComparingTo("6000");

        mvc.perform(post("/goals/{id}/money", g.getId()).with(csrf()).param("amount", "4000")
                        .param("direction", "in").param("record", "true"))
                .andExpect(redirectedUrl("/goals"));
        assertThat(goals.findById(g.getId()).orElseThrow().getSavedAmount()).isEqualByComparingTo("10000");
        assertThat(transactions.findByDateBetweenOrderByDateAscIdAsc(AppTime.today(), AppTime.today()))
                .anySatisfy(t -> {
                    assertThat(t.getType()).isEqualTo(TxType.TRANSFER);
                    assertThat(t.getCategory().getCode()).isEqualTo("DEPOSIT");
                    assertThat(t.getAmount()).isEqualByComparingTo("4000");
                });

        mvc.perform(post("/goals/{id}/money", g.getId()).with(csrf()).param("amount", "99999").param("direction", "out"))
                .andExpect(redirectedUrl("/goals"));
        assertThat(goals.findById(g.getId()).orElseThrow().getSavedAmount()).isEqualByComparingTo("0");

        mvc.perform(get("/goals").param("lang", "en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample trip")))
                .andExpect(content().string(containsString("฿30,000.00")));
        mvc.perform(get("/goals").param("lang", "th")).andExpect(status().isOk())
                .andExpect(content().string(containsString("เป้าหมายการออม")));
    }

    @Test
    void goalFormRejectsBadInput() throws Exception {
        mvc.perform(post("/goals").with(csrf()).param("name", " ").param("target", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type a name.")));
        assertThat(goals.findAll()).noneMatch(g -> g.getName().isBlank());
    }

    @Test
    void goalCanBeDeleted() throws Exception {
        Goal g = goals.save(new Goal("Sample delete", Money.of(100), Money.zero(), null));
        mvc.perform(get("/goals/{id}/edit", g.getId())).andExpect(status().isOk());
        mvc.perform(post("/goals/{id}/delete", g.getId()).with(csrf())).andExpect(redirectedUrl("/goals"));
        assertThat(goals.findById(g.getId())).isEmpty();
    }

    // ---------------------------------------------------------------- accounts and net worth

    @Test
    void netWorthAddsAccountsAndSubtractsDebts() throws Exception {
        var before = netWorth.now();
        var beforeHistory = netWorth.history(12);
        mvc.perform(post("/accounts").with(csrf()).param("name", "Sample bank").param("type", "BANK")
                        .param("balance", "50000").param("active", "true"))
                .andExpect(redirectedUrl("/accounts"));
        mvc.perform(post("/accounts").with(csrf()).param("name", "Sample card").param("type", "CREDIT")
                        .param("balance", "3000").param("active", "true"))
                .andExpect(redirectedUrl("/accounts"));
        Account bank = accounts.findAll().stream().filter(a -> a.getName().equals("Sample bank")).findFirst().orElseThrow();

        Installment plan = new Installment("Sample phone", Money.of(12000), Money.of(1000), 12,
                AppTime.thisMonth().atDay(1));
        plan.setPaidCount(2);
        installments.save(plan);

        var nw = netWorth.now();
        assertThat(nw.cash().subtract(before.cash())).isEqualByComparingTo("50000");
        assertThat(nw.credit().subtract(before.credit())).isEqualByComparingTo("3000");
        assertThat(nw.installmentDebt().subtract(before.installmentDebt())).isEqualByComparingTo("10000");
        assertThat(nw.total().subtract(before.total())).isEqualByComparingTo(Money.of(50000 - 3000 - 10000));

        // Update balances: today's balance replaces the one typed earlier today.
        mvc.perform(post("/accounts/balances").with(csrf()).param("date", AppTime.today().toString())
                        .param("balance_" + bank.getId(), "52,500.50"))
                .andExpect(redirectedUrl("/accounts"));
        assertThat(netWorth.now().cash().subtract(before.cash())).isEqualByComparingTo("52500.50");
        assertThat(balances.findByAccountOrderByDateDescIdDesc(bank)).hasSize(1);

        var history = netWorth.history(12);
        assertThat(history).hasSize(12);
        assertThat(history.get(11).cash()).isEqualByComparingTo(netWorth.now().cash());
        assertThat(history.get(10).cash()).isEqualByComparingTo(beforeHistory.get(10).cash());   // not counted before its first balance

        mvc.perform(get("/accounts")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample card")));
        mvc.perform(get("/api/accounts/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.total.length()").value(12));
        mvc.perform(get("/accounts/{id}/edit", bank.getId())).andExpect(status().isOk());
    }

    @Test
    void futureBalanceDateIsRefused() throws Exception {
        Account a = accounts.save(new Account("Sample wallet", AccountType.WALLET, Money.of(100)));
        mvc.perform(post("/accounts/balances").with(csrf()).param("date", AppTime.today().plusDays(2).toString())
                        .param("balance_" + a.getId(), "500"))
                .andExpect(redirectedUrl("/accounts"))
                .andExpect(flash().attribute("flashError", "acc.error.date"));
    }

    @Test
    void deletingAnAccountKeepsItsTransactions() throws Exception {
        Account a = accounts.save(new Account("Sample old", AccountType.BANK, Money.of(100)));
        Transaction t = tx(LocalDate.of(2031, 5, 3), "SHOPPING", "250", "Sample shoes");
        t.setAccount(a);
        transactions.save(t);
        mvc.perform(post("/accounts/{id}/delete", a.getId()).with(csrf())).andExpect(redirectedUrl("/accounts"));
        assertThat(accounts.findById(a.getId())).isEmpty();
        assertThat(transactions.findById(t.getId())).isPresent();
    }

    @Test
    void installmentDebtFollowsTheSchedule() {
        Installment plan = new Installment("Sample", Money.of(3000), Money.of(1000), 3, LocalDate.of(2031, 1, 1));
        assertThat(NetWorthService.debtAt(plan, YearMonth.of(2030, 12))).isEqualByComparingTo("0");
        assertThat(NetWorthService.debtAt(plan, YearMonth.of(2031, 1))).isEqualByComparingTo("2000");
        assertThat(NetWorthService.debtAt(plan, YearMonth.of(2031, 3))).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- reports

    private void sampleYear() {
        tx(LocalDate.of(2031, 1, 28), "SALARY", "40000", "Sample salary");
        tx(LocalDate.of(2031, 1, 5), "RENT", "8000", "Sample rent");
        tx(LocalDate.of(2031, 1, 10), "EAT_LIFESTYLE", "3000", "Sample food");
        tx(LocalDate.of(2031, 1, 12), "DEPOSIT", "5000", "Sample deposit");
        tx(LocalDate.of(2031, 2, 27), "SALARY", "40000", "Sample salary");
        tx(LocalDate.of(2031, 2, 10), "EAT_LIFESTYLE", "4500", "=cmd|' /C calc'!A0");   // looks like a formula
    }

    @Test
    void yearlyReportAddsUpMonthsAndGroups() throws Exception {
        sampleYear();
        var r = reports.year(2031);
        assertThat(r.rows()).hasSize(12);
        assertThat(r.total().summary().income()).isEqualByComparingTo("80000");
        assertThat(r.total().summary().expense()).isEqualByComparingTo("15500");
        assertThat(r.total().summary().transfer()).isEqualByComparingTo("5000");
        assertThat(r.total().group(com.moneymgmt.domain.BudgetGroup.EAT)).isEqualByComparingTo("7500");
        assertThat(r.rows().get(0).summary().remaining()).isEqualByComparingTo("24000");
        assertThat(r.averageIncome()).isEqualByComparingTo("40000");

        var c = reports.compare(YearMonth.of(2031, 1), YearMonth.of(2031, 2));
        var eat = c.rows().stream().filter(x -> x.category().getCode().equals("EAT_LIFESTYLE")).findFirst().orElseThrow();
        assertThat(eat.diff()).isEqualByComparingTo("1500");
        assertThat(eat.diffPercent()).isEqualByComparingTo("50");
        var rent = c.rows().stream().filter(x -> x.category().getCode().equals("RENT")).findFirst().orElseThrow();
        assertThat(rent.b()).isEqualByComparingTo("0");

        mvc.perform(get("/reports").param("year", "2031").param("a", "2031-01").param("b", "2031-02").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("฿80,000.00")))
                .andExpect(content().string(containsString("Compare two months")));
        mvc.perform(get("/api/reports/year").param("year", "2031"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.income[0]").value(40000.00));
    }

    @Test
    void transactionsCsvIsUtf8AndSafeInSpreadsheets() throws Exception {
        sampleYear();
        var res = mvc.perform(get("/reports/export/transactions.csv").param("year", "2031"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("transactions-2031.csv")))
                .andReturn().getResponse();
        String csv = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿date,type,category_en");
        assertThat(csv).contains("2031-01-28,INCOME,Salary,เงินเดือน,,40000.00");
        assertThat(csv).contains(",'=cmd|' /C calc'!A0,");   // quoted so it is not run as a formula
        assertThat(csv.lines().count()).isEqualTo(7);
    }

    @Test
    void fullBackupHasEveryTable() throws Exception {
        sampleYear();
        byte[] xlsx = mvc.perform(get("/reports/export/backup.xlsx"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("transaction_record")).isNotNull();
            assertThat(wb.getSheet("category").getRow(0).getCell(1).getStringCellValue()).isEqualTo("code");
            assertThat(wb.getNumberOfSheets()).isEqualTo(com.moneymgmt.service.DataExportService.TABLES.size());
            assertThat(wb.getSheet("transaction_record").getLastRowNum()).isGreaterThanOrEqualTo(6);
        }
    }

    // ---------------------------------------------------------------- categories and settings

    @Test
    void categoryCanBeAddedRenamedAndHidden() throws Exception {
        mvc.perform(post("/settings/categories").with(csrf()).param("type", "EXPENSE").param("nameEn", "Pet food")
                        .param("nameTh", "อาหารสัตว์").param("budgetGroup", "NECESSARY").param("sortOrder", "330")
                        .param("active", "true"))
                .andExpect(redirectedUrl("/settings/categories"));
        var c = categories.findByCode("PET_FOOD").orElseThrow();
        assertThat(c.getNameTh()).isEqualTo("อาหารสัตว์");

        // The type cannot change, and a transfer group is refused for an expense.
        mvc.perform(post("/settings/categories").with(csrf()).param("id", c.getId().toString()).param("type", "INCOME")
                        .param("nameEn", "Pet food").param("nameTh", "อาหารสัตว์").param("budgetGroup", "SAVING")
                        .param("sortOrder", "330"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose a budget group.")));

        mvc.perform(post("/settings/categories").with(csrf()).param("id", c.getId().toString()).param("nameEn", "Pets")
                        .param("nameTh", "สัตว์เลี้ยง").param("budgetGroup", "SHOPPING").param("sortOrder", "420")
                        .param("_active", "on"))
                .andExpect(redirectedUrl("/settings/categories"));
        c = categories.findById(c.getId()).orElseThrow();
        assertThat(c.getNameEn()).isEqualTo("Pets");
        assertThat(c.getType()).isEqualTo(TxType.EXPENSE);
        assertThat(c.isActive()).isFalse();   // checkbox not sent = hidden

        mvc.perform(get("/settings/categories")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Pets")));
        mvc.perform(get("/settings/categories/new").param("type", "TRANSFER")).andExpect(status().isOk());
    }

    @Test
    void settingsShowsCopyAndSecurityStatus() throws Exception {
        mvc.perform(get("/settings").param("lang", "en")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Not set up yet.")))
                .andExpect(content().string(containsString("Two-step code")))
                .andExpect(content().string(not(containsString("Copy now"))));
    }

    @Test
    void languageSwitchKeepsTheQuery() throws Exception {
        mvc.perform(get("/transactions?month=2031-02"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/transactions?month=2031-02&amp;lang=th\"")));
    }
}
