package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Credit card 0 % campaign: tick purchases of a month and turn each into its own installment plan. Made-up data. */
@WithMockUser(username = "tester")
@Transactional
class SplitInstallmentsTest extends AbstractWebTest {

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    private Transaction tx(LocalDate date, String code, String amount, String description) {
        var c = categories.findByCode(code).orElseThrow();
        return transactions.save(new Transaction(date, c.getType(), Money.of(amount), c, description));
    }

    @Test
    void tickedPurchasesBecomeZeroPercentPlans() throws Exception {
        Transaction tv = tx(LocalDate.of(2032, 4, 9), "SHOPPING", "9000", "Sample TV");
        Transaction dinner = tx(LocalDate.of(2032, 4, 12), "EAT_LIFESTYLE", "1000", "Sample dinner");
        Transaction keep = tx(LocalDate.of(2032, 4, 15), "SHOPPING", "500", "Sample socks");
        Transaction rent = tx(LocalDate.of(2032, 4, 1), "RENT", "8000", "Sample rent");

        mvc.perform(get("/transactions/installments").param("month", "2032-04").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample TV")))
                .andExpect(content().string(not(containsString("Sample rent"))));   // fixed costs are not offered

        mvc.perform(post("/transactions/installments").with(csrf()).param("month", "2032-04").param("months", "3")
                        .param("ids", tv.getId().toString(), dinner.getId().toString(), rent.getId().toString()))
                .andExpect(redirectedUrl("/transactions?month=2032-04"))
                .andExpect(flash().attribute("flash", "split.done"));

        Transaction t = transactions.findWithDetailsById(tv.getId()).orElseThrow();
        assertThat(t.getAmount()).isEqualByComparingTo("3000");
        assertThat(t.getInstallmentNo()).isEqualTo(1);
        assertThat(t.getDate()).isEqualTo(LocalDate.of(2032, 4, 9));
        var plan = t.getInstallment();
        assertThat(plan.getFullPrice()).isEqualByComparingTo("9000");
        assertThat(plan.getMonths()).isEqualTo(3);
        assertThat(plan.getInterestPercent()).isEqualByComparingTo("0");
        assertThat(plan.getPaidCount()).isEqualTo(1);
        assertThat(plan.getStartMonth()).isEqualTo(LocalDate.of(2032, 4, 1));

        Transaction d = transactions.findWithDetailsById(dinner.getId()).orElseThrow();
        assertThat(d.getAmount()).isEqualByComparingTo("333.33");
        assertThat(d.getInstallment()).isNotNull();
        assertThat(d.getInstallment().getId()).isNotEqualTo(plan.getId());   // one plan per purchase

        assertThat(transactions.findWithDetailsById(keep.getId()).orElseThrow().getInstallment()).isNull();
        assertThat(transactions.findWithDetailsById(rent.getId()).orElseThrow().getInstallment()).isNull();

        // Already split: no longer offered.
        mvc.perform(get("/transactions/installments").param("month", "2032-04"))
                .andExpect(content().string(not(containsString("Sample TV"))))
                .andExpect(content().string(containsString("Sample socks")));
    }

    @Test
    void nothingTickedShowsAnError() throws Exception {
        mvc.perform(post("/transactions/installments").with(csrf()).param("month", "2032-04"))
                .andExpect(redirectedUrl("/transactions/installments?month=2032-04"))
                .andExpect(flash().attribute("flashError", "split.error.none"));
    }
}
