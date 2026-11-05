package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Asset;
import com.moneymgmt.repo.AssetRepository;
import com.moneymgmt.repo.AssetTradeRepository;
import com.moneymgmt.repo.DividendRepository;
import com.moneymgmt.repo.FxRateRepository;
import com.moneymgmt.repo.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Investment pages outside a test transaction, with made-up assets named "Sample…" (removed afterwards). */
@WithMockUser(username = "tester")
class InvestmentPagesTest extends AbstractWebTest {

    @Autowired AssetRepository assets;
    @Autowired AssetTradeRepository trades;
    @Autowired DividendRepository dividends;
    @Autowired FxRateRepository fxRates;
    @Autowired TransactionRepository transactions;

    @AfterEach
    void cleanUp() {
        assets.deleteAll(assets.findAll().stream().filter(a -> a.getName().startsWith("Sample")).toList());
        fxRates.deleteAll(fxRates.findAll().stream().filter(r -> r.getDate().getYear() == 2036).toList());
        transactions.deleteAll(transactions.findAll().stream().filter(t -> t.getDate().getYear() == 2036).toList());
    }

    private Asset asset(String name) {
        return assets.findAll().stream().filter(a -> a.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void fullFlowAddAssetTradeDividendPrices() throws Exception {
        mvc.perform(get("/investments")).andExpect(status().isOk());
        mvc.perform(get("/investments/assets/new")).andExpect(status().isOk());

        mvc.perform(post("/investments/assets").with(csrf()).param("name", "Sample ETF").param("type", "ETF")
                        .param("currency", "USD"))
                .andExpect(status().is3xxRedirection());
        Asset etf = asset("Sample ETF");

        mvc.perform(get("/investments/{id}/trades/new", etf.getId()).param("side", "BUY")).andExpect(status().isOk());
        // Units and amount given: the price is worked out (455.08 / 0.6428064 = 707.958104).
        mvc.perform(post("/investments/{id}/trades", etf.getId()).with(csrf()).param("side", "BUY")
                        .param("date", "2036-03-26").param("units", "0.6428064").param("amount", "455.08")
                        .param("fee", "0.49").param("fxRate", "32.96").param("addTransaction", "true"))
                .andExpect(redirectedUrl("/investments/" + etf.getId()))
                .andExpect(flash().attribute("flash", "inv.tradeSavedWithTx"));
        var t = trades.findByAssetOrderByTradeDateAscIdAsc(etf).get(0);
        assertThat(t.getPricePerUnit()).isEqualByComparingTo("707.958104");
        // Added to Transactions as Investment: (455.08 + 0.49) × 32.96 = 15,015.59
        assertThat(transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc("INVESTMENT",
                java.time.LocalDate.of(2036, 1, 1), java.time.LocalDate.of(2036, 12, 31)))
                .singleElement().satisfies(x -> assertThat(x.getAmount()).isEqualByComparingTo("15015.59"));

        // USD asset needs a rate.
        mvc.perform(post("/investments/{id}/trades", etf.getId()).with(csrf()).param("side", "BUY")
                        .param("date", "2036-03-27").param("units", "1").param("price", "700").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("exchange rate")));

        mvc.perform(post("/investments/{id}/dividends", etf.getId()).with(csrf()).param("date", "2036-06-27")
                        .param("amount", "0.70").param("withholding", "0.10"))
                .andExpect(redirectedUrl("/investments/" + etf.getId()));
        assertThat(dividends.findByAssetOrderByPayDateAscIdAsc(etf)).hasSize(1);

        mvc.perform(get("/investments/prices")).andExpect(status().isOk());
        mvc.perform(post("/investments/prices").with(csrf()).param("date", "2036-07-01").param("usd", "33.5")
                        .param("price_" + etf.getId(), "760.25"))
                .andExpect(redirectedUrl("/investments"));
        assertThat(asset("Sample ETF").getCurrentPrice()).isEqualByComparingTo("760.25");

        mvc.perform(get("/investments/{id}", etf.getId()).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Buy and sell history")))
                .andExpect(content().string(containsString("$760.25")));
        mvc.perform(get("/investments").param("lang", "th"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sample ETF")))
                .andExpect(content().string(containsString("คำศัพท์")));
        mvc.perform(get("/api/investments/charts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byAsset[?(@.name=='Sample ETF')]").exists())
                .andExpect(jsonPath("$.history.months").isArray());

        var trade = trades.findByAssetOrderByTradeDateAscIdAsc(etf).get(0);
        mvc.perform(get("/investments/{id}/trades/{t}/edit", etf.getId(), trade.getId())).andExpect(status().isOk());
        mvc.perform(post("/investments/{id}/trades/{t}/delete", etf.getId(), trade.getId()).with(csrf()))
                .andExpect(redirectedUrl("/investments/" + etf.getId()));
        assertThat(trades.findByAssetOrderByTradeDateAscIdAsc(etf)).isEmpty();

        mvc.perform(post("/investments/{id}/delete", etf.getId()).with(csrf())).andExpect(redirectedUrl("/investments"));
        assertThat(assets.findById(etf.getId())).isEmpty();
    }

    @Test
    void projectionJson() throws Exception {
        mvc.perform(get("/api/investments/projection").param("monthly", "7,000").param("rate", "10")
                        .param("years", "1").param("start", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.final.contributed").value(84000.00))
                .andExpect(jsonPath("$.final.value").value(87958.98));
        mvc.perform(get("/api/investments/projection").param("years", "999").param("rate", "abc"))
                .andExpect(jsonPath("$.years").value(50))
                .andExpect(jsonPath("$.rate").value(10));
    }

    @Test
    void badValuesShowTheFormAgain() throws Exception {
        mvc.perform(post("/investments/assets").with(csrf()).param("name", "").param("type", "ETF")
                        .param("currency", "USD").param("lang", "en"))
                .andExpect(status().isOk());
        mvc.perform(post("/investments/assets").with(csrf()).param("name", "Sample Fund").param("type", "MUTUAL_FUND")
                        .param("currency", "THB").param("price", "27.8797").param("priceDate", "2036-01-05"))
                .andExpect(status().is3xxRedirection());
        Asset fund = asset("Sample Fund");
        mvc.perform(post("/investments/{id}/trades", fund.getId()).with(csrf()).param("side", "BUY")
                        .param("date", "2036-01-05").param("units", "").param("price", "").param("amount", "10000")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("at least two")));
        mvc.perform(post("/investments/prices").with(csrf()).param("date", "2036-01-06")
                        .param("price_" + fund.getId(), "-1").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("above 0")));
    }
}
