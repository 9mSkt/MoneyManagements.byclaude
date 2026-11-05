package com.moneymgmt.service;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetPrice;
import com.moneymgmt.domain.AssetTrade;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Dividend;
import com.moneymgmt.domain.FxRate;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.AssetPriceRepository;
import com.moneymgmt.repo.AssetRepository;
import com.moneymgmt.repo.AssetTradeRepository;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.DividendRepository;
import com.moneymgmt.repo.FxRateRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saving assets, trades, dividends and the prices / rates the owner types in. */
@Service
public class InvestmentService {

    /** Values of a buy or sell, already checked. {@code fxRate} is null for THB assets. */
    public record TradeInput(LocalDate date, TradeSide side, BigDecimal units, BigDecimal price, BigDecimal amount,
                             BigDecimal fee, BigDecimal fxRate) {
    }

    private final AssetRepository assets;
    private final AssetTradeRepository trades;
    private final DividendRepository dividends;
    private final AssetPriceRepository prices;
    private final FxRateRepository fxRates;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;

    public InvestmentService(AssetRepository assets, AssetTradeRepository trades, DividendRepository dividends,
                             AssetPriceRepository prices, FxRateRepository fxRates,
                             TransactionRepository transactions, CategoryRepository categories) {
        this.assets = assets;
        this.trades = trades;
        this.dividends = dividends;
        this.prices = prices;
        this.fxRates = fxRates;
        this.transactions = transactions;
        this.categories = categories;
    }

    /**
     * Adds or changes a trade. A new buy can also be added to Transactions as an "Investment" transfer in baht
     * (amount + fee, times the rate), so the goal budget's Investment share counts it.
     */
    @Transactional
    public AssetTrade saveTrade(Asset asset, Long tradeId, TradeInput in, boolean addTransaction) {
        AssetTrade t;
        if (tradeId != null) {
            t = trades.findById(tradeId).filter(x -> x.getAsset().getId().equals(asset.getId())).orElseThrow();
            t.update(in.date(), in.side(), in.amount(), in.fxRate(), in.price(), in.units(), in.fee());
        } else {
            t = new AssetTrade(asset, in.date(), in.side(), in.amount(), asset.getCurrency(), in.fxRate(), in.price(),
                    in.units(), in.fee());
        }
        trades.save(t);
        if (tradeId == null && addTransaction && in.side() == TradeSide.BUY) {
            Category cat = categories.findByCode("INVESTMENT").orElseThrow();
            BigDecimal fx = in.fxRate() == null ? BigDecimal.ONE : in.fxRate();
            BigDecimal baht = Money.scale(in.amount().add(in.fee()).multiply(fx));
            Transaction tx = new Transaction(in.date(), cat.getType(), baht, cat, asset.getName());
            tx.setNote(in.units().stripTrailingZeros().toPlainString() + " @ " + in.price().stripTrailingZeros().toPlainString()
                    + " " + asset.getCurrency());
            transactions.save(tx);
        }
        // A trade is also a known price on that day.
        if (asset.getPriceDate() == null || !in.date().isBefore(asset.getPriceDate())) {
            asset.setCurrentPrice(in.price());
            asset.setPriceDate(in.date());
            assets.save(asset);
        }
        return t;
    }

    @Transactional
    public Dividend saveDividend(Asset asset, Long id, LocalDate date, BigDecimal amount, BigDecimal withholding,
                                 BigDecimal fxRate) {
        Dividend d;
        if (id != null) {
            d = dividends.findById(id).filter(x -> x.getAsset().getId().equals(asset.getId())).orElseThrow();
            d.update(date, amount, withholding, fxRate);
        } else {
            d = new Dividend(asset, date, amount, withholding, asset.getCurrency(), fxRate);
        }
        return dividends.save(d);
    }

    /**
     * Saves the prices typed on one day (asset id → price) and the USD/THB rate. An asset's current price
     * moves to the new one unless it already has a later price.
     */
    @Transactional
    public void updatePrices(LocalDate date, Map<Long, BigDecimal> newPrices, BigDecimal usdThb) {
        newPrices.forEach((id, price) -> assets.findById(id).ifPresent(a -> {
            AssetPrice p = prices.findByAssetAndDate(a, date).orElseGet(() -> new AssetPrice(a, date, price));
            p.setPrice(price);
            prices.save(p);
            if (a.getPriceDate() == null || !date.isBefore(a.getPriceDate())) {
                a.setCurrentPrice(price);
                a.setPriceDate(date);
                assets.save(a);
            }
        }));
        if (usdThb != null) {
            FxRate r = fxRates.findByCurrencyAndDate("USD", date).orElseGet(() -> new FxRate("USD", date, usdThb));
            r.setRate(usdThb);
            fxRates.save(r);
        }
    }
}
