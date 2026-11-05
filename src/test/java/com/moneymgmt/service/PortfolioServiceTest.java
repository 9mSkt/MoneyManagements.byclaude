package com.moneymgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.Dividend;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.repo.AssetRepository;
import com.moneymgmt.repo.DividendRepository;
import com.moneymgmt.service.InvestmentService.TradeInput;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Made-up funds and trades; every test is rolled back. */
@Transactional
class PortfolioServiceTest extends AbstractWebTest {

    @Autowired PortfolioService portfolio;
    @Autowired InvestmentService investments;
    @Autowired AssetRepository assets;
    @Autowired DividendRepository dividends;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private TradeInput trade(String date, TradeSide side, String units, String price, String fee, String fx) {
        BigDecimal u = d(units);
        BigDecimal p = d(price);
        return new TradeInput(LocalDate.parse(date), side, u, p, Money.scale(u.multiply(p)), Money.of(fee),
                fx == null ? null : d(fx));
    }

    @Test
    void averageCostRealizedProfitAndValueForAThaiFund() {
        Asset fund = assets.save(new Asset("Sample S&P fund", AssetType.MUTUAL_FUND, "THB"));
        investments.saveTrade(fund, null, trade("2035-01-10", TradeSide.BUY, "400", "25", "0", null), false);
        investments.saveTrade(fund, null, trade("2035-02-10", TradeSide.BUY, "500", "20", "0", null), false);
        investments.updatePrices(LocalDate.of(2035, 3, 1), Map.of(fund.getId(), d("24")), null);

        var h = portfolio.holding(fund);
        assertThat(h.units()).isEqualByComparingTo("900");
        assertThat(h.costThb()).isEqualByComparingTo("20000");
        assertThat(h.avgCost()).isEqualByComparingTo("22.222222");
        assertThat(h.valueThb()).isEqualByComparingTo("21600");
        assertThat(h.unrealizedThb()).isEqualByComparingTo("1600");
        assertThat(h.profitPercent()).isEqualByComparingTo("8.00");

        // Sell 300 at 26: proceeds 7,800, cost removed 20,000 × 300/900 = 6,666.67 → realized 1,133.33.
        investments.saveTrade(fund, null, trade("2035-03-05", TradeSide.SELL, "300", "26", "0", null), false);
        h = portfolio.holding(fund);
        assertThat(h.units()).isEqualByComparingTo("600");
        assertThat(h.costThb()).isEqualByComparingTo("13333.33");
        assertThat(h.realizedThb()).isEqualByComparingTo("1133.33");
        assertThat(h.avgCost()).as("average cost does not change on a sale").isEqualByComparingTo("22.222222");
        // The sale is the latest known price.
        assertThat(h.price()).isEqualByComparingTo("26");
    }

    @Test
    void usdEtfUsesTheTradeRateForCostAndTheLatestRateForValue() {
        Asset etf = assets.save(new Asset("Sample VGT", AssetType.ETF, "USD"));
        investments.saveTrade(etf, null, trade("2035-04-01", TradeSide.BUY, "1", "700", "1", "33"), false);
        investments.updatePrices(LocalDate.of(2035, 5, 1), Map.of(etf.getId(), d("750")), d("34"));
        dividends.save(new Dividend(etf, LocalDate.of(2035, 4, 20), Money.of("10"), Money.of("1.50"), "USD", null));

        var h = portfolio.holding(etf);
        assertThat(h.costCcy()).isEqualByComparingTo("701");
        assertThat(h.costThb()).isEqualByComparingTo("23133");          // 701 × 33
        assertThat(h.valueCcy()).isEqualByComparingTo("750");
        assertThat(h.fx()).isEqualByComparingTo("34");
        assertThat(h.valueThb()).isEqualByComparingTo("25500");         // 750 × 34
        assertThat(h.unrealizedThb()).isEqualByComparingTo("2367");
        assertThat(h.profitPercentCcy()).isEqualByComparingTo("6.99");  // 49 / 701
        // Dividend 10 − 1.50 tax = 8.50, at the rate known on 20 Apr (33): 280.50.
        assertThat(h.dividendsCcy()).isEqualByComparingTo("8.50");
        assertThat(h.dividendsThb()).isEqualByComparingTo("280.50");
        assertThat(h.withholdingCcy()).isEqualByComparingTo("1.50");
        assertThat(h.totalReturnThb()).isEqualByComparingTo("2647.50");
    }

    @Test
    void valueOverTimeFollowsUnitsAndKnownPrices() {
        Asset fund = assets.save(new Asset("Sample history fund", AssetType.MUTUAL_FUND, "THB"));
        investments.saveTrade(fund, null, trade("2025-01-15", TradeSide.BUY, "100", "10", "0", null), false);
        investments.updatePrices(LocalDate.of(2025, 2, 20), Map.of(fund.getId(), d("12")), null);
        var points = portfolio.valueOverTime();
        var jan = points.stream().filter(p -> p.month().toString().equals("2025-01")).findFirst().orElseThrow();
        var feb = points.stream().filter(p -> p.month().toString().equals("2025-02")).findFirst().orElseThrow();
        assertThat(jan.value()).isGreaterThanOrEqualTo(Money.of("1000"));
        assertThat(feb.value().subtract(jan.value())).isEqualByComparingTo("200");
    }

    @Test
    void dcaProjectionCompoundsMonthly() {
        var rows = PortfolioService.projection(Money.zero(), Money.of("7000"), d("10"), 2);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).contributed()).isEqualByComparingTo("84000");
        assertThat(rows.get(1).value()).isEqualByComparingTo("87958.98");
        assertThat(rows.get(2).value()).isGreaterThan(rows.get(1).value().multiply(d("2")));
        var noGrowth = PortfolioService.projection(Money.of("1000"), Money.of("100"), d("0"), 1);
        assertThat(noGrowth.get(1).value()).isEqualByComparingTo("2200");
    }
}
