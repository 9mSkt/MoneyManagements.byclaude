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
                .andExpect(content().string(not(containsString("name=\"ids\" value=\"" + tv.getId() + "\""))))
                .andExpect(content().string(containsString("Sample socks")));
    }

    @Autowired com.moneymgmt.service.PostingService posting;
    @Autowired com.moneymgmt.repo.InstallmentRepository plans;

    @Test
    void planCanBeRevisedAndCancelled() throws Exception {
        Transaction tv = tx(LocalDate.of(2032, 6, 9), "SHOPPING", "9000", "Sample TV");
        mvc.perform(post("/transactions/installments").with(csrf()).param("month", "2032-06").param("months", "3")
                .param("ids", tv.getId().toString())).andExpect(redirectedUrl("/transactions?month=2032-06"));
        Long planId = transactions.findWithDetailsById(tv.getId()).orElseThrow().getInstallment().getId();
        posting.postPlan(planId, java.time.YearMonth.of(2032, 7));   // payment 2/3
        var plan = plans.findById(planId).orElseThrow();
        assertThat(transactions.findByInstallmentOrderByDateAscIdAsc(plan)).hasSize(2);

        mvc.perform(get("/transactions/installments").param("month", "2032-06").param("lang", "en"))
                .andExpect(content().string(containsString("Plans started this month")));
        mvc.perform(get("/transactions/{id}/edit", tv.getId()).param("lang", "en"))
                .andExpect(content().string(containsString("Change or cancel")));
        mvc.perform(get("/fixed-costs/installments/{id}/edit", planId).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cancel installments")));

        // 3 → 6 months: both payments become 1,500.
        mvc.perform(post("/fixed-costs/installments/{id}/revise", planId).with(csrf()).param("months", "6"))
                .andExpect(redirectedUrl("/fixed-costs/installments/" + planId + "/edit"));
        plan = plans.findById(planId).orElseThrow();
        assertThat(plan.getMonths()).isEqualTo(6);
        assertThat(plan.getMonthlyAmount()).isEqualByComparingTo("1500");
        assertThat(transactions.findByInstallmentOrderByDateAscIdAsc(plan))
                .hasSize(2).allSatisfy(t -> assertThat(t.getAmount()).isEqualByComparingTo("1500"));

        // 6 → 1 month: payment 2 is removed, payment 1 is the full price.
        mvc.perform(post("/fixed-costs/installments/{id}/revise", planId).with(csrf()).param("months", "1"));
        plan = plans.findById(planId).orElseThrow();
        assertThat(plan.getPaidCount()).isEqualTo(1);
        assertThat(transactions.findByInstallmentOrderByDateAscIdAsc(plan)).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("9000"));

        // Back to 3 months with payment 2 posted again, then cancel.
        mvc.perform(post("/fixed-costs/installments/{id}/revise", planId).with(csrf()).param("months", "3"));
        posting.postPlan(planId, java.time.YearMonth.of(2032, 7));
        mvc.perform(post("/fixed-costs/installments/{id}/cancel", planId).with(csrf()))
                .andExpect(redirectedUrl("/transactions?month=2032-06"))
                .andExpect(flash().attribute("flash", "inst.cancelled"));
        assertThat(plans.findById(planId)).isEmpty();
        Transaction back = transactions.findWithDetailsById(tv.getId()).orElseThrow();
        assertThat(back.getAmount()).isEqualByComparingTo("9000");
        assertThat(back.getInstallment()).isNull();
        assertThat(back.getInstallmentNo()).isNull();
        assertThat(transactions.findByDateBetweenOrderByDateAscIdAsc(LocalDate.of(2032, 7, 1), LocalDate.of(2032, 7, 31)))
                .noneMatch(t -> "Sample TV".equals(t.getDescription()));
        // It can be split again.
        mvc.perform(get("/transactions/installments").param("month", "2032-06"))
                .andExpect(content().string(containsString("name=\"ids\" value=\"" + tv.getId() + "\"")));
    }

    @Test
    void nothingTickedShowsAnError() throws Exception {
        mvc.perform(post("/transactions/installments").with(csrf()).param("month", "2032-04"))
                .andExpect(redirectedUrl("/transactions/installments?month=2032-04"))
                .andExpect(flash().attribute("flashError", "split.error.none"));
    }
}
