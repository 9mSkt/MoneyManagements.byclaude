package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.BudgetKind;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.BudgetRuleRepository;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.BudgetService;
import com.moneymgmt.util.Money;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Dashboard and budget pages outside a test transaction, with made-up entries in 2034 (removed afterwards). */
@WithMockUser(username = "tester")
class BudgetPagesTest extends AbstractWebTest {

    static final YearMonth M = YearMonth.of(2034, 5);

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    @Autowired BudgetRuleRepository rules;
    @Autowired BudgetService budgets;

    @AfterEach
    void cleanUp() {
        transactions.deleteAll(transactions.findAll().stream().filter(t -> t.getDate().getYear() == 2034).toList());
        rules.deleteAll(rules.findAll().stream().filter(r -> r.getMonth().getYear() == 2034).toList());
    }

    private Transaction tx(int day, String code, String amount, String description) {
        var c = categories.findByCode(code).orElseThrow();
        return transactions.save(new Transaction(M.atDay(day), c.getType(), Money.of(amount), c, description));
    }

    private void sample() {
        tx(1, "SALARY", "40000", "Sample salary");
        tx(1, "CAR_PAYMENT", "10000", "Sample car");           // base 30,000 → shopping 40 % = 12,000
        tx(4, "SHOPPING", "11000", "Sample sofa");
        tx(9, "SHOPPING", "2000", "Sample lamp");               // crosses 12,000
        tx(6, "DEPOSIT", "3000", "Sample saving");
    }

    @Test
    void dashboardShowsRealNumbersAndChartJson() throws Exception {
        sample();
        mvc.perform(get("/").param("month", M.toString()).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("฿40,000.00")))
                .andExpect(content().string(containsString("฿23,000.00")))
                .andExpect(content().string(containsString("Over by ฿1,000.00")));
        mvc.perform(get("/api/dashboard").param("month", M.toString()).param("lang", "th"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.income[11]").value(40000.00))
                .andExpect(jsonPath("$.trend.outcome[11]").value(23000.00))
                .andExpect(jsonPath("$.groups[?(@.key=='SHOPPING')].value").value(13000.00))
                .andExpect(jsonPath("$.sankey.nodes[0].name").value("รายรับ"))
                .andExpect(jsonPath("$.daily.days.length()").value(31))
                .andExpect(jsonPath("$.daily.days[3][1]").value(11000.00));
    }

    @Test
    void emptyMonthStillRenders() throws Exception {
        mvc.perform(get("/").param("month", "2034-01")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").param("month", "2034-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sankey.nodes.length()").value(0));
        mvc.perform(get("/budget").param("month", "2034-01")).andExpect(status().isOk());
    }

    @Test
    void budgetPageAndRedRowsOnTransactions() throws Exception {
        sample();
        mvc.perform(get("/budget").param("month", M.toString()).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Actual budget")))
                .andExpect(content().string(containsString("Goal budget")))
                .andExpect(content().string(containsString("Sample lamp")));
        mvc.perform(get("/transactions").param("month", M.toString()).param("lang", "en"))
                .andExpect(content().string(containsString("tx-row over")))
                .andExpect(content().string(containsString("Red rows pushed")));
    }

    @Test
    void editBudgetForThisMonthOnly() throws Exception {
        sample();
        mvc.perform(get("/budget/edit").param("month", M.toString()).param("kind", "ACTUAL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"40\"")));

        mvc.perform(post("/budget/edit").with(csrf()).param("month", M.toString()).param("kind", "ACTUAL")
                        .param("scope", "month")
                        .param("mode_EAT", "PERCENT").param("value_EAT", "40")
                        .param("mode_SHOPPING", "AMOUNT").param("value_SHOPPING", "15,000")
                        .param("mode_GAS", "PERCENT").param("value_GAS", "20")
                        .param("mode_NECESSARY", "NONE"))
                .andExpect(redirectedUrl("/budget?month=" + M))
                .andExpect(flash().attribute("flash", "budget.savedMonth"));
        assertThat(budgets.overBudgetIds(M)).as("shopping now fits in 15,000").isEmpty();
        assertThat(budgets.rulesInForce(BudgetKind.ACTUAL, M.plusMonths(1)).get(BudgetGroup.SHOPPING).getPercent())
                .isEqualByComparingTo("40");

        mvc.perform(post("/budget/edit").with(csrf()).param("month", M.toString()).param("kind", "GOAL")
                        .param("mode_EAT", "PERCENT").param("value_EAT", "150").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from 0 to 100")));
    }
}
