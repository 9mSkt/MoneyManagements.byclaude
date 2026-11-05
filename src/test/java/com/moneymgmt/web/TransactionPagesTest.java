package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.repo.RecurringItemRepository;
import com.moneymgmt.repo.TransactionRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Pages run outside a test transaction, as in the real app, so lazy-loading mistakes show up.
 * Uses made-up entries in 2031 and removes everything it created afterwards.
 */
@WithMockUser(username = "tester")
class TransactionPagesTest extends AbstractWebTest {

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    @Autowired RecurringItemRepository items;
    @Autowired InstallmentRepository plans;

    @AfterEach
    void cleanUp() {
        transactions.deleteAll(transactions.findAll().stream()
                .filter(t -> t.getDate().getYear() == 2031).toList());
        items.deleteAll(items.findAll().stream().filter(r -> r.getName().startsWith("Sample")).toList());
        plans.deleteAll(plans.findAll().stream().filter(p -> p.getItem().startsWith("Sample")).toList());
    }

    private Long catId(String code) {
        return categories.findByCode(code).orElseThrow().getId();
    }

    private List<Transaction> in2031(String description) {
        return transactions.findAll().stream()
                .filter(t -> t.getDate().getYear() == 2031 && description.equals(t.getDescription())).toList();
    }

    @Test
    void quickAddSavesAndListShowsIt() throws Exception {
        mvc.perform(post("/transactions").with(csrf())
                        .param("type", "EXPENSE").param("amount", "1,250.50")
                        .param("categoryId", catId("EAT_LIFESTYLE").toString())
                        .param("date", "2031-03-14").param("description", "Sample lunch")
                        .param("tags", "work, team,, work").param("returnTo", "/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("flash", "tx.saved"));

        Transaction t = in2031("Sample lunch").get(0);
        assertThat(t.getAmount()).isEqualByComparingTo("1250.50");
        assertThat(t.getTags()).isEqualTo("work, team");

        mvc.perform(get("/transactions").param("month", "2031-03").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample lunch")))
                .andExpect(content().string(containsString("−฿1,250.50")))
                .andExpect(content().string(containsString("#work #team")));
    }

    @Test
    void filtersAndSearchReturnOnlyResultsForHtmx() throws Exception {
        save("2031-04-02", "EAT_LIFESTYLE", "Sample noodles", "120");
        save("2031-04-03", "GASOLINE", "Sample fuel", "900");
        save("2031-04-04", "OTHER_INCOME", "Sample refund", "300");

        mvc.perform(get("/transactions").param("month", "2031-04").param("q", "NOODLE")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample noodles")))
                .andExpect(content().string(not(containsString("Sample fuel"))))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(containsString("hx-swap-oob")));

        mvc.perform(get("/transactions").param("month", "2031-04").param("group", "GAS"))
                .andExpect(content().string(containsString("Sample fuel")))
                .andExpect(content().string(not(containsString("Sample noodles"))));

        mvc.perform(get("/transactions").param("month", "2031-04").param("type", "INCOME"))
                .andExpect(content().string(containsString("Sample refund")))
                .andExpect(content().string(not(containsString("Sample fuel"))));

        mvc.perform(get("/transactions").param("month", "all").param("q", "Sample"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample fuel")));
    }

    @Test
    void invalidAmountShowsTheFormAgain() throws Exception {
        mvc.perform(post("/transactions").with(csrf())
                        .param("type", "EXPENSE").param("amount", "abc")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-05-01")
                        .param("description", "Sample bad").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type an amount")));
        mvc.perform(post("/transactions").with(csrf())
                        .param("amount", "10").param("categoryId", catId("SHOPPING").toString())
                        .param("date", "not-a-date").param("description", "Sample bad"))
                .andExpect(status().isOk());
        assertThat(in2031("Sample bad")).isEmpty();
    }

    @Test
    void editAndDelete() throws Exception {
        save("2031-06-10", "SHOPPING", "Sample shirt", "590");
        Long id = in2031("Sample shirt").get(0).getId();

        mvc.perform(get("/transactions/{id}/edit", id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample shirt")));

        mvc.perform(post("/transactions").with(csrf()).param("id", id.toString())
                        .param("amount", "650").param("categoryId", catId("SHOPPING").toString())
                        .param("date", "2031-06-11").param("description", "Sample shirt"))
                .andExpect(redirectedUrl("/transactions?month=2031-06"));
        assertThat(transactions.findById(id)).get()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("650"));

        mvc.perform(post("/transactions/{id}/delete", id).with(csrf()))
                .andExpect(redirectedUrl("/transactions?month=2031-06"));
        assertThat(transactions.findById(id)).isEmpty();

        mvc.perform(get("/transactions/{id}/edit", id)).andExpect(status().isNotFound());
    }

    @Test
    void returnToOtherSitesIsIgnored() throws Exception {
        mvc.perform(post("/transactions").with(csrf()).param("amount", "1")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-07-01")
                        .param("description", "Sample redirect").param("returnTo", "//evil.example"))
                .andExpect(redirectedUrl("/transactions?month=2031-07"));
    }

    @Test
    void incentiveEntryIsSavedAndUpdatedNotDuplicated() throws Exception {
        mvc.perform(get("/transactions/incentive").param("month", "2031-08")).andExpect(status().isOk());
        for (String amount : List.of("4,000", "4500")) {
            mvc.perform(post("/transactions/incentive").with(csrf())
                            .param("month", "2031-08").param("date", "2031-08-25").param("amount", amount)
                            .param("kpiPercent", "95.5").param("incentivePercent", "10").param("bonus", "2000"))
                    .andExpect(redirectedUrl("/transactions?month=2031-08"));
        }
        var month = transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc("INCENTIVE",
                LocalDate.of(2031, 8, 1), LocalDate.of(2031, 8, 31));
        assertThat(month).singleElement().satisfies(t -> {
            assertThat(t.getAmount()).isEqualByComparingTo("4500");
            assertThat(t.getKpiPercent()).isEqualByComparingTo("95.5");
            assertThat(t.getIncentivePercent()).isEqualByComparingTo("10");
        });
        assertThat(transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc("BONUS",
                LocalDate.of(2031, 8, 1), LocalDate.of(2031, 8, 31))).hasSize(1);

        mvc.perform(get("/transactions").param("month", "2031-08").param("lang", "en"))
                .andExpect(content().string(containsString("KPI 95.5%")));

        // Date outside the month is refused; empty bonus removes the bonus.
        mvc.perform(post("/transactions/incentive").with(csrf()).param("month", "2031-08")
                        .param("date", "2031-09-01").param("amount", "4500"))
                .andExpect(status().isOk());
        mvc.perform(post("/transactions/incentive").with(csrf()).param("month", "2031-08")
                        .param("date", "2031-08-25").param("amount", "0").param("bonus", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc("BONUS",
                LocalDate.of(2031, 8, 1), LocalDate.of(2031, 8, 31))).isEmpty();
    }

    @Test
    void fixedCostsPageItemAndInstallmentForms() throws Exception {
        mvc.perform(get("/fixed-costs")).andExpect(status().isOk());
        mvc.perform(get("/fixed-costs/items/new")).andExpect(status().isOk());
        mvc.perform(get("/fixed-costs/installments/new")).andExpect(status().isOk());

        mvc.perform(post("/fixed-costs/items").with(csrf()).param("name", "Sample internet")
                        .param("amount", "599").param("categoryId", catId("INTERNET").toString())
                        .param("dayOfMonth", "15").param("active", "true"))
                .andExpect(redirectedUrl("/fixed-costs"));
        RecurringItem item = items.findAll().stream().filter(r -> r.getName().equals("Sample internet")).findFirst()
                .orElseThrow();

        mvc.perform(post("/fixed-costs/items").with(csrf()).param("name", "")
                        .param("amount", "x").param("categoryId", catId("INTERNET").toString())
                        .param("dayOfMonth", "40"))
                .andExpect(status().isOk());

        mvc.perform(post("/fixed-costs/installments").with(csrf()).param("item", "Sample laptop")
                        .param("fullPrice", "24,000").param("months", "10").param("interestPercent", "0")
                        .param("startMonth", "2031-01").param("paidCount", "2")
                        .param("categoryId", catId("SHOPPING").toString()))
                .andExpect(redirectedUrl("/fixed-costs"));
        Installment plan = plans.findAll().stream().filter(p -> p.getItem().equals("Sample laptop")).findFirst()
                .orElseThrow();
        assertThat(plan.getMonthlyAmount()).isEqualByComparingTo("2400");
        assertThat(plan.getRemainingDebt()).isEqualByComparingTo("19200");

        mvc.perform(post("/fixed-costs/installments").with(csrf()).param("item", "Sample bad plan")
                        .param("fullPrice", "1000").param("months", "abc").param("startMonth", "2031-01")
                        .param("paidCount", "0").param("categoryId", catId("SHOPPING").toString()))
                .andExpect(status().isOk());

        // Post both for March 2031 by hand, then adjust the fixed cost amount.
        mvc.perform(post("/fixed-costs/post-item/{id}", item.getId()).with(csrf()).param("month", "2031-03"))
                .andExpect(redirectedUrl("/fixed-costs?month=2031-03"));
        mvc.perform(post("/fixed-costs/post-plan/{id}", plan.getId()).with(csrf()).param("month", "2031-03"))
                .andExpect(redirectedUrl("/fixed-costs?month=2031-03"));
        Transaction posted = in2031("Sample internet").get(0);
        assertThat(posted.getDate()).isEqualTo(LocalDate.of(2031, 3, 15));
        assertThat(in2031("Sample laptop")).singleElement()
                .satisfies(t -> assertThat(t.getInstallmentNo()).isEqualTo(3));

        mvc.perform(post("/fixed-costs/posted/{id}", posted.getId()).with(csrf())
                        .param("amount", "650.25").param("month", "2031-03"))
                .andExpect(redirectedUrl("/fixed-costs?month=2031-03"));
        assertThat(transactions.findById(posted.getId())).get()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("650.25"));

        mvc.perform(get("/fixed-costs").param("month", "2031-03").param("lang", "th"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample laptop")))
                .andExpect(content().string(containsString("3/10")))
                .andExpect(content().string(containsString("ลงรายการแล้ว")));
        mvc.perform(get("/fixed-costs/items/{id}/edit", item.getId())).andExpect(status().isOk());
        mvc.perform(get("/fixed-costs/installments/{id}/edit", plan.getId())).andExpect(status().isOk());
        mvc.perform(get("/transactions/{id}/edit", posted.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample internet")));
        mvc.perform(get("/transactions").param("month", "2031-03")).andExpect(status().isOk())
                .andExpect(content().string(containsString("3/10")));

        mvc.perform(post("/fixed-costs/items/{id}/delete", item.getId()).with(csrf()))
                .andExpect(redirectedUrl("/fixed-costs"));
        assertThat(items.findById(item.getId())).isEmpty();
        assertThat(transactions.findById(posted.getId())).as("posted entry stays").isPresent();
    }

    @Autowired com.moneymgmt.service.PostingService posting;

    @Test
    void periodOnFirstPaymentStartsAPlanAndLaterMonthsArePosted() throws Exception {
        mvc.perform(post("/transactions").with(csrf()).param("type", "EXPENSE").param("amount", "4,500")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-09-20")
                        .param("description", "Sample earbuds").param("period", " 1 / 3 "))
                .andExpect(redirectedUrl("/transactions?month=2031-09"))
                .andExpect(flash().attribute("flash", "tx.savedInstallment"));

        Installment plan = plans.findAll().stream().filter(p -> p.getItem().equals("Sample earbuds")).findFirst()
                .orElseThrow();
        assertThat(plan.getMonths()).isEqualTo(3);
        assertThat(plan.getPaidCount()).isEqualTo(1);
        assertThat(plan.getMonthlyAmount()).isEqualByComparingTo("1500");
        assertThat(plan.getFullPrice()).isEqualByComparingTo("4500");
        assertThat(plan.getStartMonth()).isEqualTo(LocalDate.of(2031, 9, 1));

        posting.postDue(LocalDate.of(2031, 9, 25));   // same month: nothing new
        posting.postDue(LocalDate.of(2031, 10, 1));
        posting.postDue(LocalDate.of(2031, 11, 1));
        posting.postDue(LocalDate.of(2031, 12, 1));   // paid off: nothing more
        assertThat(in2031("Sample earbuds")).extracting(Transaction::getInstallmentNo)
                .containsExactlyInAnyOrder(1, 2, 3);

        mvc.perform(get("/transactions").param("month", "2031-11"))
                .andExpect(content().string(containsString("3/3")));

        // The Transactions page lists the plans with a payment that month.
        mvc.perform(get("/transactions").param("month", "2031-10").param("lang", "en"))
                .andExpect(content().string(containsString("Installments this month (1)")))
                .andExpect(content().string(containsString("First Sep 2031")))
                .andExpect(content().string(containsString("2/3")));
        mvc.perform(get("/transactions").param("month", "2031-12").param("lang", "en"))
                .andExpect(content().string(not(containsString("Installments this month"))));
        mvc.perform(get("/transactions/new").param("lang", "th"))
                .andExpect(content().string(containsString("ตัวอย่างรายการผ่อน")));

        // First payment month = the month the 1/3 entry was added; schedule lists each payment.
        mvc.perform(get("/fixed-costs").param("lang", "en"))
                .andExpect(content().string(containsString("Sep 2031 · 1/3")))
                .andExpect(content().string(containsString("Nov 2031 · 3/3")));
        mvc.perform(get("/fixed-costs/installments/{id}/edit", plan.getId()).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment schedule")))
                .andExpect(content().string(containsString("September 2031")))
                .andExpect(content().string(containsString("October 2031")))
                .andExpect(content().string(containsString("20 Sep 2031")));
        Long first = in2031("Sample earbuds").stream().filter(t -> t.getInstallmentNo() == 1).findFirst()
                .orElseThrow().getId();
        mvc.perform(get("/transactions/{id}/edit", first).param("lang", "en"))
                .andExpect(content().string(containsString("value=\"1/3\"")))
                .andExpect(content().string(containsString("Part of an installment plan")));
    }

    @Test
    void fullPriceIsSplitAndCreditFeeIsAddedToThePayment() throws Exception {
        mvc.perform(post("/transactions").with(csrf()).param("amount", "1,000")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-02-14")
                        .param("description", "Sample headphones").param("period", "1/3").param("fee", "25"))
                .andExpect(status().is3xxRedirection());
        Installment plan = plans.findAll().stream().filter(p -> p.getItem().equals("Sample headphones")).findFirst()
                .orElseThrow();
        assertThat(plan.getFullPrice()).isEqualByComparingTo("1000");
        assertThat(plan.getMonthlyAmount()).isEqualByComparingTo("333.33");
        Transaction first = in2031("Sample headphones").get(0);
        assertThat(first.getAmount()).isEqualByComparingTo("358.33");
        assertThat(first.getFee()).isEqualByComparingTo("25");

        // A later payment where only the minimum was paid: add the card fee on its edit page.
        posting.postDue(LocalDate.of(2031, 3, 1));
        Transaction second = in2031("Sample headphones").stream().filter(t -> t.getInstallmentNo() == 2)
                .findFirst().orElseThrow();
        mvc.perform(get("/transactions/{id}/edit", second.getId()).param("lang", "en"))
                .andExpect(content().string(containsString("Credit fee / interest")));
        mvc.perform(post("/transactions").with(csrf()).param("id", second.getId().toString())
                        .param("amount", "333.33").param("fee", "41.20")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-03-01")
                        .param("description", "Sample headphones"))
                .andExpect(status().is3xxRedirection());
        assertThat(transactions.findById(second.getId())).get().satisfies(t -> {
            assertThat(t.getAmount()).isEqualByComparingTo("374.53");
            assertThat(t.getFee()).isEqualByComparingTo("41.20");
        });
        mvc.perform(get("/transactions/{id}/edit", second.getId()))
                .andExpect(content().string(containsString("value=\"333.33\"")))
                .andExpect(content().string(containsString("value=\"41.20\"")));
        mvc.perform(get("/transactions").param("month", "2031-03").param("lang", "en"))
                .andExpect(content().string(containsString("incl. fee ฿41.20")));

        // Bad fee is refused.
        mvc.perform(post("/transactions").with(csrf()).param("amount", "900").param("fee", "abc")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-02-14")
                        .param("description", "Sample bad fee").param("period", "1/3").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type the fee")));
    }

    @Test
    void periodStartingMidPlanAndBadPeriods() throws Exception {
        mvc.perform(post("/transactions").with(csrf()).param("amount", "800")
                        .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-05-03")
                        .param("description", "Sample sofa").param("period", "3/4"))
                .andExpect(status().is3xxRedirection());
        Installment sofa = plans.findAll().stream().filter(p -> p.getItem().equals("Sample sofa")).findFirst()
                .orElseThrow();
        assertThat(sofa.getStartMonth()).isEqualTo(LocalDate.of(2031, 3, 1));
        assertThat(sofa.getPaidCount()).isEqualTo(3);

        for (String bad : List.of("4/3", "0/3", "abc", "1/200")) {
            mvc.perform(post("/transactions").with(csrf()).param("amount", "100")
                            .param("categoryId", catId("SHOPPING").toString()).param("date", "2031-05-03")
                            .param("description", "Sample bad period").param("period", bad).param("lang", "en"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("payment number / number of months")));
        }
        assertThat(in2031("Sample bad period")).isEmpty();

        // Income ignores the period field.
        mvc.perform(post("/transactions").with(csrf()).param("amount", "100")
                        .param("categoryId", catId("OTHER_INCOME").toString()).param("date", "2031-05-03")
                        .param("description", "Sample income").param("period", "1/3"))
                .andExpect(flash().attribute("flash", "tx.saved"));
    }

    @Test
    void quickAddDrawerIsOnEveryPageInBothLanguages() throws Exception {
        mvc.perform(get("/").param("lang", "th"))
                .andExpect(content().string(containsString("name=\"categoryId\"")))
                .andExpect(content().string(containsString("รายจ่าย")));
        mvc.perform(get("/settings").param("lang", "en"))
                .andExpect(content().string(containsString("Saving / invest")));
    }

    private void save(String date, String code, String description, String amount) throws Exception {
        mvc.perform(post("/transactions").with(csrf()).param("amount", amount)
                        .param("categoryId", catId(code).toString()).param("date", date)
                        .param("description", description))
                .andExpect(status().is3xxRedirection());
    }
}
