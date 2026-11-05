package com.moneymgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.BudgetKind;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.BudgetService.GroupBudget;
import com.moneymgmt.service.BudgetService.Setting;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Made-up sample month in 2033; each test is rolled back. Starter rules: actual Eat 40 / Shopping 40 / Gas 20. */
@Transactional
class BudgetServiceTest extends AbstractWebTest {

    static final YearMonth M = YearMonth.of(2033, 3);

    @Autowired BudgetService budgets;
    @Autowired StatsService stats;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    private Transaction tx(int day, String code, String amount) {
        var c = categories.findByCode(code).orElseThrow();
        return transactions.save(new Transaction(M.atDay(day), c.getType(), Money.of(amount), c, "Sample " + code));
    }

    private void sampleMonth() {
        tx(1, "SALARY", "50000");
        tx(1, "RENT", "10000");          // fixed cost → base = 40,000
        tx(2, "EAT_LIFESTYLE", "10000");
        tx(5, "GASOLINE", "3000");
        tx(3, "DEPOSIT", "5000");        // saving transfer
    }

    private GroupBudget group(BudgetService.KindBudget kb, BudgetGroup g) {
        return kb.groups().stream().filter(x -> x.group() == g).findFirst().orElseThrow();
    }

    @Test
    void budgetsAreWorkedOutFromIncomeAfterFixedCost() {
        sampleMonth();
        var b = budgets.forMonth(M);
        assertThat(b.summary().income()).isEqualByComparingTo("50000");
        assertThat(b.summary().fixedCost()).isEqualByComparingTo("10000");
        assertThat(b.summary().base()).isEqualByComparingTo("40000");
        assertThat(b.summary().remaining()).isEqualByComparingTo("22000");   // 50000 − 23000 − 5000
        assertThat(b.summary().savingRate()).isEqualByComparingTo("54.00");  // (50000 − 23000) / 50000

        GroupBudget eat = group(b.actual(), BudgetGroup.EAT);
        assertThat(eat.budget()).isEqualByComparingTo("16000");
        assertThat(eat.spent()).isEqualByComparingTo("10000");
        assertThat(eat.level()).isEqualTo("ok");
        GroupBudget gas = group(b.actual(), BudgetGroup.GAS);
        assertThat(gas.budget()).isEqualByComparingTo("8000");
        assertThat(group(b.actual(), BudgetGroup.NECESSARY).hasBudget()).isFalse();

        GroupBudget saving = group(b.goal(), BudgetGroup.SAVING);   // goal: Deposit 10 % = 4,000; saved 5,000
        assertThat(saving.budget()).isEqualByComparingTo("4000");
        assertThat(saving.level()).as("saving past the target is good, not red").isEqualTo("done");
    }

    @Test
    void levelsTurnAmberAt80AndRedAbove100() {
        var warn = new GroupBudget(BudgetGroup.EAT, null, null, Money.of("1000"), Money.of("800"));
        var over = new GroupBudget(BudgetGroup.EAT, null, null, Money.of("1000"), Money.of("1000.01"));
        var full = new GroupBudget(BudgetGroup.EAT, null, null, Money.of("1000"), Money.of("1000"));
        assertThat(warn.level()).isEqualTo("warn");
        assertThat(full.level()).isEqualTo("warn");
        assertThat(over.level()).isEqualTo("over");
        assertThat(over.width()).isEqualByComparingTo("100");
        assertThat(over.left()).isEqualByComparingTo("-0.01");
    }

    @Test
    void entryThatCrossesTheBudgetAndLaterOnesAreMarked() {
        sampleMonth();                                    // eat budget 16,000; 10,000 spent on the 2nd
        Transaction under = tx(10, "EAT_LIFESTYLE", "5000");    // 15,000: still under
        Transaction crosses = tx(12, "EAT_LIFESTYLE", "1500");  // 16,500: crosses
        Transaction after = tx(20, "EAT_LIFESTYLE", "100");
        assertThat(budgets.overBudgetIds(M)).contains(crosses.getId(), after.getId())
                .doesNotContain(under.getId());
    }

    @Test
    void changeForThisMonthOnlyLeavesNextMonthAsItWas() {
        sampleMonth();
        budgets.save(BudgetKind.ACTUAL, M, Map.of(
                BudgetGroup.EAT, new Setting(Money.of("50"), null),
                BudgetGroup.SHOPPING, new Setting(Money.of("40"), null),
                BudgetGroup.GAS, new Setting(null, Money.of("5000"))), false);

        var now = budgets.forMonth(M).actual();
        assertThat(group(now, BudgetGroup.EAT).budget()).isEqualByComparingTo("20000");
        assertThat(group(now, BudgetGroup.GAS).budget()).isEqualByComparingTo("5000");
        assertThat(group(now, BudgetGroup.GAS).isByAmount()).isTrue();

        var next = budgets.rulesInForce(BudgetKind.ACTUAL, M.plusMonths(1));
        assertThat(next.get(BudgetGroup.EAT).getPercent()).isEqualByComparingTo("40");
        assertThat(next.get(BudgetGroup.GAS).getFixedAmount()).isNull();
        assertThat(next.get(BudgetGroup.GAS).getPercent()).isEqualByComparingTo("20");
        var before = budgets.rulesInForce(BudgetKind.ACTUAL, M.minusMonths(1));
        assertThat(before.get(BudgetGroup.EAT).getPercent()).isEqualByComparingTo("40");
    }

    @Test
    void changeForLaterMonthsReplacesLaterChanges() {
        YearMonth later = M.plusMonths(3);
        budgets.save(BudgetKind.GOAL, later, Map.of(BudgetGroup.EAT, new Setting(Money.of("25"), null)), true);
        budgets.save(BudgetKind.GOAL, M, Map.of(BudgetGroup.EAT, new Setting(Money.of("20"), null)), true);
        assertThat(budgets.rulesInForce(BudgetKind.GOAL, later.plusMonths(6)).get(BudgetGroup.EAT).getPercent())
                .isEqualByComparingTo("20");
        // Groups left empty in the form mean "no budget" from this month on.
        assertThat(budgets.forMonth(later).goal().groups().stream()
                .filter(g -> g.group() == BudgetGroup.SHOPPING).findFirst().orElseThrow().hasBudget()).isFalse();
    }

    @Test
    void beforePaydayTheBudgetUsesLastMonthsIncome() {
        sampleMonth();                                          // March: income 50,000, fixed 10,000 → base 40,000
        YearMonth april = M.plusMonths(1);
        var eat = categories.findByCode("EAT_LIFESTYLE").orElseThrow();
        transactions.save(new Transaction(april.atDay(3), eat.getType(), Money.of("1000"), eat, "Sample april lunch"));
        var b = budgets.forMonth(april);
        assertThat(b.estimated()).isTrue();
        assertThat(b.base()).isEqualByComparingTo("40000");
        assertThat(group(b.actual(), BudgetGroup.EAT).budget()).isEqualByComparingTo("16000");
        assertThat(budgets.overBudgetIds(april)).as("no red rows before payday").isEmpty();

        // Once April's salary is entered, April's own numbers are used.
        var salary = categories.findByCode("SALARY").orElseThrow();
        transactions.save(new Transaction(april.atDay(28), salary.getType(), Money.of("30000"), salary, "Sample"));
        b = budgets.forMonth(april);
        assertThat(b.estimated()).isFalse();
        assertThat(b.base()).isEqualByComparingTo("30000");
        assertThat(b.summary().income()).as("income counts in the month it is received").isEqualByComparingTo("30000");
    }

    @Test
    void statsGroupByMonthAndDay() {
        sampleMonth();
        tx(2, "SHOPPING", "250.50");
        var months = stats.months(M.minusMonths(1), M);
        assertThat(months).hasSize(2);
        assertThat(months.get(0).isEmpty()).isTrue();
        assertThat(months.get(1).expense()).isEqualByComparingTo("23250.50");
        var daily = stats.dailySpending(M);
        assertThat(daily.get(LocalDate.of(2033, 3, 2))).isEqualByComparingTo("10250.50");
        assertThat(daily).as("fixed costs left out").doesNotContainKey(LocalDate.of(2033, 3, 1));
        assertThat(StatsService.byGroup(stats.totals(M, M)).get(BudgetGroup.SAVING)).isEqualByComparingTo("5000");
    }
}
