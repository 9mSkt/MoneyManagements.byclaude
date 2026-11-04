package com.moneymgmt.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void roundsHalfUpToTwoDecimals() {
        assertThat(Money.scale(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
        assertThat(Money.scale(new BigDecimal("10.004"))).isEqualByComparingTo("10.00");
        assertThat(Money.of("9897").scale()).isEqualTo(2);
    }

    @Test
    void percentHandlesZero() {
        assertThat(Money.percent(Money.of("13550"), Money.of("52000"))).isEqualByComparingTo("26.06");
        assertThat(Money.percent(Money.of("1"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void formatsWithThousandsSeparator() {
        assertThat(Money.format(new BigDecimal("1234567.5"))).isEqualTo("1,234,567.50");
    }
}
