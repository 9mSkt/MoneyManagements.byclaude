package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Transactions shown as one card per category (default) or as the list by date. Made-up data. */
@WithMockUser(username = "tester")
@Transactional
class TransactionCardsTest extends AbstractWebTest {

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    private Transaction tx(LocalDate date, String code, String amount, String description) {
        var c = categories.findByCode(code).orElseThrow();
        return transactions.save(new Transaction(date, c.getType(), Money.of(amount), c, description));
    }

    @Test
    void cardsGroupByBudgetGroupWithTotalsAndShare() {
        var rows = List.of(
                tx(LocalDate.of(2033, 3, 9), "EAT_LIFESTYLE", "300", "Sample lunch"),
                tx(LocalDate.of(2033, 3, 8), "SHOPPING", "700", "Sample shirt"),
                tx(LocalDate.of(2033, 3, 6), "RENT", "8000", "Sample rent"),
                tx(LocalDate.of(2033, 3, 5), "ELECTRICITY", "1000", "Sample bill"),
                tx(LocalDate.of(2033, 3, 1), "SALARY", "40000", "Sample salary"),
                tx(LocalDate.of(2033, 3, 1), "BONUS", "10000", "Sample bonus"));
        var cards = TransactionController.GroupCard.of(rows, Set.of(rows.get(1).getId()));
        assertThat(cards).extracting(TransactionController.GroupCard::key)
                .containsExactly("INCOME", "FIXED", "SHOPPING", "EAT");
        var fixed = cards.get(1);
        assertThat(fixed.rows()).extracting(Transaction::getDescription).containsExactly("Sample rent", "Sample bill");
        assertThat(fixed.total()).isEqualByComparingTo("9000");
        assertThat(fixed.share()).isEqualByComparingTo("90");   // of 10,000 spent
        assertThat(cards.get(0).total()).isEqualByComparingTo("50000");
        assertThat(cards.get(0).type()).isEqualTo(TxType.INCOME);
        assertThat(cards.get(2).over()).isEqualTo(1);
    }

    @Test
    void longGroupsShowFiveThenMore() {
        for (int d = 1; d <= 7; d++) {
            tx(LocalDate.of(2033, 4, d), "GASOLINE", "100", "Sample fuel " + d);
        }
        var card = TransactionController.GroupCard.of(
                transactions.findByDateBetweenOrderByDateAscIdAsc(LocalDate.of(2033, 4, 1), LocalDate.of(2033, 4, 30)), Set.of()).get(0);
        assertThat(card.first()).hasSize(5);
        assertThat(card.rest()).hasSize(2);
    }

    @Test
    void pageShowsCardsByDefaultAndTheListByDate() throws Exception {
        tx(LocalDate.of(2033, 5, 9), "EAT_LIFESTYLE", "300", "Sample lunch");
        tx(LocalDate.of(2033, 5, 8), "SHOPPING", "700", "Sample shirt");
        mvc.perform(get("/transactions?month=2033-05&lang=en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"card cat-card")))
                .andExpect(content().string(containsString("By group")))
                .andExpect(content().string(containsString("Sample shirt")))
                .andExpect(content().string(not(containsString("class=\"tx-date\""))));
        mvc.perform(get("/transactions?month=2033-05&view=date&lang=en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"tx-date\"")))
                .andExpect(content().string(not(containsString("class=\"card cat-card"))))
                // month arrows keep the chosen view
                .andExpect(content().string(containsString("month=2033-06&amp;view=date")));
    }
}
