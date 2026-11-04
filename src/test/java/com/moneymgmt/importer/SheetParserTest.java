package com.moneymgmt.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.importer.ImportPlan.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.WorkbookFactory;

class SheetParserTest {

    static ImportPlan plan;

    @BeforeAll
    static void parse() throws Exception {
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(SampleWorkbook.build()))) {
            // Evaluate formulas so the Incentive cell has a cached value, as in a file downloaded from Google Sheets.
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            plan = SheetParser.parse(new ByteArrayInputStream(out.toByteArray()));
        }
    }

    private static TxLine tx(LocalDate month, String description) {
        return plan.transactions().stream()
                .filter(t -> t.date().equals(month) && t.description().equals(description))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + description + " in " + month));
    }

    @Test
    void readsBothMonthsDatedOnTheFirst() {
        assertThat(plan.months()).extracting(MonthSummary::month)
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    }

    @Test
    void readsIncomeIncludingIncentivePercentAndTheExtraBonusRow() {
        LocalDate feb = LocalDate.of(2026, 2, 1);
        assertThat(tx(feb, "Salary").amount()).isEqualByComparingTo("20000.00");
        assertThat(tx(feb, "Bonus").category().code()).isEqualTo("BONUS");
        TxLine incentive = tx(feb, "Incentive");
        assertThat(incentive.amount()).isEqualByComparingTo("2000.00");
        assertThat(incentive.incentivePercent()).isEqualByComparingTo("10.00");
        assertThat(plan.months().get(1).income()).isEqualByComparingTo("37200.00");
    }

    @Test
    void readsFixedCostsEvenWhenRowsAreShifted() {
        LocalDate feb = LocalDate.of(2026, 2, 1);
        assertThat(tx(feb, "Car").category().code()).isEqualTo("CAR_PAYMENT");
        assertThat(tx(feb, "Mom").category().code()).isEqualTo("FAMILY");
        assertThat(plan.months().get(1).fixedCost()).isEqualByComparingTo("9600.50");
        TxLine gym = tx(feb, "Gym membership");
        assertThat(gym.category().needsReview()).isTrue();
        assertThat(gym.category().group()).isEqualTo(BudgetGroup.FIXED);
    }

    @Test
    void readsSpendingBlocksIntoTheirGroups() {
        LocalDate jan = LocalDate.of(2026, 1, 1);
        assertThat(tx(jan, "Gas").category().code()).isEqualTo("GASOLINE");
        assertThat(tx(jan, "Music app").category().code()).isEqualTo("NECESSARY");
        assertThat(tx(jan, "Book shop").category().code()).isEqualTo("SHOPPING");
        assertThat(tx(jan, "Cinema").category().code()).isEqualTo("EAT_LIFESTYLE");
        assertThat(tx(jan, "Refund").amount()).isEqualByComparingTo("-100.00");
    }

    @Test
    void installmentUsesCorrectMonthlyAmountAndTracksProgress() {
        TxLine laptop = tx(LocalDate.of(2026, 1, 1), "Laptop");
        // 30,000 / 3 + 30,000 × 0.5 % = 10,150 (the sheet's cached 9,999 is replaced)
        assertThat(laptop.amount()).isEqualByComparingTo("10150.00");
        assertThat(laptop.installmentNo()).isEqualTo(1);
        assertThat(plan.installments()).singleElement().satisfies(i -> {
            assertThat(i.months()).isEqualTo(3);
            assertThat(i.paidCount()).isEqualTo(2);
            assertThat(i.startMonth()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(i.interestPercent()).isEqualByComparingTo("0.5");
        });
        assertThat(plan.notes()).extracting(Note::key).contains("import.note.installmentFix", "import.note.negative");
    }

    @Test
    void recurringItemsComeFromTheLatestMonth() {
        assertThat(plan.recurring()).extracting(RecurringLine::name)
                .containsExactly("Mom", "Car", "Gym membership", "Electric Bill");
    }

    @Test
    void budgetPlanIsRecordedOnlyWhenItChanges() {
        assertThat(plan.budgetRules()).hasSize(3)
                .allSatisfy(b -> assertThat(b.month()).isEqualTo(LocalDate.of(2026, 1, 1)));
        assertThat(plan.budgetRules()).filteredOn(b -> b.group() == BudgetGroup.EAT)
                .singleElement().satisfies(b -> assertThat(b.percent()).isEqualByComparingTo("40"));
    }

    @Test
    void readsFundAndEtfTradesWithCurrencyAndDividends() {
        assertThat(plan.assets()).hasSize(2);
        AssetLine fund = plan.assets().get(0);
        assertThat(fund.type()).isEqualTo(AssetType.MUTUAL_FUND);
        assertThat(fund.currency()).isEqualTo("THB");
        assertThat(fund.trades()).singleElement().satisfies(t -> assertThat(t.units()).isEqualByComparingTo("400"));

        AssetLine etf = plan.assets().get(1);
        assertThat(etf.name()).isEqualTo("ABC");
        assertThat(etf.trades()).hasSize(2);
        TradeLine sell = etf.trades().get(1);
        assertThat(sell.side()).isEqualTo(TradeSide.SELL);
        assertThat(sell.amount()).isEqualByComparingTo("110.00");
        assertThat(etf.trades().get(0).fxRate()).isEqualByComparingTo("34");
        assertThat(etf.dividends()).singleElement().satisfies(d -> {
            assertThat(d.amount()).isEqualByComparingTo("1.20");
            assertThat(d.withholdingTax()).isEqualByComparingTo("0.18");
        });
        assertThat(etf.currentPrice()).isEqualByComparingTo("110");
    }

    @Test
    void readsTaxInputs() {
        assertThat(plan.taxYears()).singleElement().satisfies(t -> {
            assertThat(t.year()).isEqualTo(2026);
            assertThat(t.monthlySalary()).isEqualByComparingTo("20000");
            assertThat(t.bonus()).isEqualByComparingTo("40000");
            assertThat(t.deductions()).extracting(DeductionLine::code).containsExactly("EXPENSE", "PERSONAL", "RMF");
        });
    }

    @Test
    void notesMentionSkippedTabs() {
        assertThat(plan.notes()).extracting(Note::key)
                .contains("import.note.months", "import.note.renovate", "import.note.summary",
                        "import.note.newCategory");
        assertThat(plan.transactions()).allSatisfy(t -> assertThat(t.type()).isIn(TxType.INCOME, TxType.EXPENSE));
    }
}
