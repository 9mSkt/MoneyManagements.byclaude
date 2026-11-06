package com.moneymgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.service.TaxCalculator.Bracket;
import com.moneymgmt.service.TaxCalculator.Inputs;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Thai PIT arithmetic with made-up numbers. */
class TaxCalculatorTest {

    static final List<Bracket> BRACKETS = List.of(
            b(0, 150_000, 0), b(150_000, 300_000, 5), b(300_000, 500_000, 10), b(500_000, 750_000, 15),
            b(750_000, 1_000_000, 20), b(1_000_000, 2_000_000, 25), b(2_000_000, 5_000_000, 30),
            new Bracket(Money.of(5_000_000), null, new BigDecimal("35")));

    static Bracket b(long from, long to, int rate) {
        return new Bracket(Money.of(from), Money.of(to), new BigDecimal(rate));
    }

    static Inputs sample(Map<String, BigDecimal> typed) {
        // 40,000 salary x 12 + 120,000 bonus; social security 750 x 12; PVD 3 %.
        return new Inputs(Money.of(600_000), Money.of(480_000), Money.of(9_000), Money.of(14_400), typed);
    }

    static Map<String, BigDecimal> typed(String... codeAndAmount) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < codeAndAmount.length; i += 2) {
            m.put(codeAndAmount[i], Money.of(codeAndAmount[i + 1]));
        }
        return m;
    }

    @Test
    void sampleYear() {
        var r = TaxCalculator.compute(sample(typed("LIFE_INSURANCE", "20000")), BRACKETS);
        // 100,000 expense + 60,000 personal + 9,000 + 14,400 + 20,000 = 203,400
        assertThat(r.totalDeductions()).isEqualByComparingTo("203400");
        assertThat(r.net()).isEqualByComparingTo("396600");
        // 150,000 x 5 % + 96,600 x 10 %
        assertThat(r.tax()).isEqualByComparingTo("17160");
        assertThat(r.marginalRate()).isEqualByComparingTo("10");
        assertThat(r.effectiveRate()).isEqualByComparingTo("2.86");
        assertThat(r.currentBracket().bracket().from()).isEqualByComparingTo("300000");
    }

    @Test
    void expenseAllowanceIsHalfOfIncomeUpTo100k() {
        var low = TaxCalculator.compute(new Inputs(Money.of(150_000), Money.of(150_000), Money.zero(), Money.zero(),
                Map.of()), BRACKETS);
        assertThat(low.deduction("EXPENSE").allowed()).isEqualByComparingTo("75000");
        assertThat(low.tax()).isEqualByComparingTo("0");
        var high = TaxCalculator.compute(sample(Map.of()), BRACKETS);
        assertThat(high.deduction("EXPENSE").allowed()).isEqualByComparingTo("100000");
        assertThat(high.deduction("EXPENSE").capped()).isTrue();
    }

    @Test
    void insuranceLimits() {
        var r = TaxCalculator.compute(sample(typed("LIFE_INSURANCE", "90000", "HEALTH_INSURANCE", "30000",
                "PARENT_HEALTH", "20000", "HOME_LOAN", "150000")), BRACKETS);
        assertThat(r.deduction("LIFE_INSURANCE").allowed()).isEqualByComparingTo("90000");
        assertThat(r.deduction("HEALTH_INSURANCE").allowed()).isEqualByComparingTo("10000");   // life + health max 100k
        assertThat(r.deduction("PARENT_HEALTH").allowed()).isEqualByComparingTo("15000");
        assertThat(r.deduction("HOME_LOAN").allowed()).isEqualByComparingTo("100000");
    }

    @Test
    void fundRoomAndLimits() {
        var r = TaxCalculator.compute(sample(typed("RMF", "30000", "THAI_ESG", "200000")), BRACKETS);
        assertThat(r.rmfRoom()).isEqualByComparingTo("150000");          // 30 % of 600,000 - 30,000
        assertThat(r.deduction("THAI_ESG").allowed()).isEqualByComparingTo("180000");
        assertThat(r.esgRoom()).isEqualByComparingTo("0");

        // PVD and RMF together are capped at 500,000.
        var rich = TaxCalculator.compute(new Inputs(Money.of(3_000_000), Money.of(3_000_000), Money.zero(),
                Money.of(450_000), typed("RMF", "200000")), BRACKETS);
        assertThat(rich.deduction("PVD").allowed()).isEqualByComparingTo("450000");
        assertThat(rich.deduction("RMF").allowed()).isEqualByComparingTo("50000");
        assertThat(rich.rmfRoom()).isEqualByComparingTo("0");
    }

    @Test
    void donationsUpTo10PercentAfterOtherDeductions() {
        var r = TaxCalculator.compute(sample(typed("LIFE_INSURANCE", "20000", "DONATION", "100000")), BRACKETS);
        // (600,000 - 203,400) x 10 %
        assertThat(r.deduction("DONATION").allowed()).isEqualByComparingTo("39660");
    }

    @Test
    void whatIfAndAmountNeeded() {
        Inputs in = sample(typed("LIFE_INSURANCE", "20000"));
        assertThat(TaxCalculator.saving(in, BRACKETS, Money.of(10_000), Money.zero())).isEqualByComparingTo("1000");
        // 2,000 at 10 % = 20,000
        assertThat(TaxCalculator.neededFor(Money.of(2_000), in, BRACKETS)).isEqualByComparingTo("20000");
        // 96,600 to leave the 10 % bracket (saves 9,660), then 340 more at 5 % = 6,800
        assertThat(TaxCalculator.neededFor(Money.of(10_000), in, BRACKETS)).isEqualByComparingTo("103400");
        // More than the whole tax cannot be saved.
        assertThat(TaxCalculator.neededFor(Money.of(50_000), in, BRACKETS)).isNull();
    }

    @Test
    void noIncomeNoTax() {
        var r = TaxCalculator.compute(new Inputs(Money.zero(), Money.zero(), Money.zero(), Money.zero(), Map.of()),
                BRACKETS);
        assertThat(r.tax()).isEqualByComparingTo("0");
        assertThat(r.net()).isEqualByComparingTo("0");
        assertThat(r.currentBracket().bracket().rate()).isEqualByComparingTo("0");
    }
}
