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
    void cardsGroupByCategoryWithTotalsAndShare() {
        var rows = List.of(
                tx(LocalDate.of(2033, 3, 9), "EAT_LIFESTYLE", "300", "Sample lunch"),
                tx(LocalDate.of(2033, 3, 8), "SHOPPING", "700", "Sample shirt"),
                tx(LocalDate.of(2033, 3, 7), "EAT_LIFESTYLE", "200", "Sample coffee"),
                tx(LocalDate.of(2033, 3, 1), "SALARY", "40000", "Sample salary"));
        var cards = TransactionController.CategoryCard.of(rows, Set.of(rows.get(1).getId()));
        assertThat(cards).extracting(c -> c.category().getCode()).containsExactly("SALARY", "SHOPPING", "EAT_LIFESTYLE");
        var eat = cards.get(2);
        assertThat(eat.total()).isEqualByComparingTo("500");
        assertThat(eat.share()).isEqualByComparingTo("41.67");   // of 1,200 spent
        assertThat(eat.rows()).extracting(Transaction::getDescription).containsExactly("Sample lunch", "Sample coffee");
        assertThat(cards.get(1).over()).isEqualTo(1);
        assertThat(cards.get(0).type()).isEqualTo(TxType.INCOME);
    }

    @Test
    void longCategoriesShowFourThenMore() {
        for (int d = 1; d <= 6; d++) {
            tx(LocalDate.of(2033, 4, d), "GASOLINE", "100", "Sample fuel " + d);
        }
        var card = TransactionController.CategoryCard.of(
                transactions.findByDateBetweenOrderByDateAscIdAsc(LocalDate.of(2033, 4, 1), LocalDate.of(2033, 4, 30)), Set.of()).get(0);
        assertThat(card.first()).hasSize(4);
        assertThat(card.rest()).hasSize(2);
    }

    @Test
    void pageShowsCardsByDefaultAndTheListByDate() throws Exception {
        tx(LocalDate.of(2033, 5, 9), "EAT_LIFESTYLE", "300", "Sample lunch");
        tx(LocalDate.of(2033, 5, 8), "SHOPPING", "700", "Sample shirt");
        mvc.perform(get("/transactions?month=2033-05&lang=en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"card cat-card")))
                .andExpect(content().string(containsString("By category")))
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
