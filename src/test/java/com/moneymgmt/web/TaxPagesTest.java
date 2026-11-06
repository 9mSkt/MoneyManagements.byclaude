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
import com.moneymgmt.repo.TaxYearRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.TaxService;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Tax planner with a made-up year 2037 (in the future, so every month not recorded is estimated). Rolled back. */
@WithMockUser(username = "tester")
@Transactional
class TaxPagesTest extends AbstractWebTest {

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    @Autowired TaxYearRepository taxYears;
    @Autowired TaxService service;

    private void tx(LocalDate date, String code, String amount) {
        var c = categories.findByCode(code).orElseThrow();
        transactions.save(new Transaction(date, c.getType(), Money.of(amount), c, "Sample " + code));
    }

    private void sampleYear() {
        tx(LocalDate.of(2037, 1, 28), "SALARY", "40000");
        tx(LocalDate.of(2037, 2, 26), "SALARY", "40000");
        tx(LocalDate.of(2037, 3, 27), "BONUS", "100000");
        tx(LocalDate.of(2037, 1, 28), "RENT_INCOME", "5000");
        tx(LocalDate.of(2037, 1, 28), "SOCIAL_SECURITY", "750");
        tx(LocalDate.of(2037, 1, 28), "PVD", "1200");
        tx(LocalDate.of(2038, 3, 20), "INCOME_TAX", "5000");   // paid when filing, the next year
        tx(LocalDate.of(2037, 3, 20), "INCOME_TAX", "9999");   // tax for 2036, not for 2037
    }

    @Test
    void planUsesRecordedIncomeAndEstimates() {
        sampleYear();
        var p = service.plan(2037);
        // salary 2 x 40,000 recorded + 10 x 40,000 estimated; housing 5,000 + 11 x 5,000; bonus 100,000
        assertThat(p.result().income()).isEqualByComparingTo("640000");
        assertThat(p.socialSecurity().total()).isEqualByComparingTo("9000");
        assertThat(p.pvd().total()).isEqualByComparingTo("14400");
        assertThat(p.paid()).isEqualByComparingTo("5000");
        assertThat(p.toPay()).isEqualByComparingTo(p.result().tax().subtract(Money.of(5000)));
    }

    @Test
    void pagesRender() throws Exception {
        sampleYear();
        mvc.perform(get("/tax").param("year", "2037")).andExpect(status().isOk())
                .andExpect(content().string(containsString("฿640,000.00")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/tax").param("year", "2037").header("Accept-Language", "th")).andExpect(status().isOk());
        mvc.perform(get("/api/tax/chart").param("year", "2037")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].rate").value("0%"));
        mvc.perform(get("/tax/whatif").param("year", "2037").param("rmf", "10000").param("save", "1000"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("whatif-box")));
        mvc.perform(get("/tax/2037/edit")).andExpect(status().isOk());
        mvc.perform(get("/tax/brackets")).andExpect(status().isOk());
    }

    @Test
    void saveNumbers() throws Exception {
        sampleYear();
        mvc.perform(post("/tax/2037").with(csrf()).param("salaryEstimate", "45,000").param("bonusExpected", "150000")
                        .param("d_LIFE_INSURANCE", "20000").param("d_RMF", "10000"))
                .andExpect(redirectedUrl("/tax?year=2037")).andExpect(flash().attribute("flash", "tax.saved"));
        var y = taxYears.findByYear(2037).orElseThrow();
        assertThat(y.getMonthlySalary()).isEqualByComparingTo("45000");
        var p = service.plan(2037);
        assertThat(p.bonusStillExpected()).isEqualByComparingTo("50000");
        assertThat(p.result().deduction("RMF").allowed()).isEqualByComparingTo("10000");
        // 80,000 + 10 x 45,000 salary, 60,000 housing, 150,000 bonus
        assertThat(p.result().income()).isEqualByComparingTo("740000");

        mvc.perform(post("/tax/2037").with(csrf()).param("d_RMF", "-5"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("has-error")));
    }

    @Test
    void bracketFormChecksTheLimits() throws Exception {
        mvc.perform(post("/tax/brackets").with(csrf()).param("to_0", "150000").param("rate_0", "0")
                        .param("to_1", "100000").param("rate_1", "5").param("rate_2", "10"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("has-error")));
        assertThat(service.brackets()).hasSize(8);
    }
}
