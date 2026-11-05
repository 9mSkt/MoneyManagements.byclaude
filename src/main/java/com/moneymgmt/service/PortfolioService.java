package com.moneymgmt.service;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetPrice;
import com.moneymgmt.domain.AssetTrade;
import com.moneymgmt.domain.Dividend;
import com.moneymgmt.domain.FxRate;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.repo.AssetPriceRepository;
import com.moneymgmt.repo.AssetRepository;
import com.moneymgmt.repo.AssetTradeRepository;
import com.moneymgmt.repo.DividendRepository;
import com.moneymgmt.repo.FxRateRepository;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolio figures worked out from the trades, dividends and the prices / USD-THB rates the owner typed in.
 *
 * <p>Cost uses the average-cost method: a buy adds amount + fee to the cost; a sell removes the average cost
 * of the units sold, and the difference between what the sale brought (amount − fee) and that cost is the
 * realized profit. Foreign trades are turned into baht with the rate of the trade, so profit in baht includes
 * the currency effect. Dividends count net of withholding tax.
 */
@Service
public class PortfolioService {

    public static final String THB = "THB";
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int UNIT_SCALE = 8;

    /** One asset: what is held now and what it has earned. Baht values are null when no USD/THB rate is known. */
    public record Holding(Asset asset, BigDecimal units, BigDecimal avgCost, BigDecimal costCcy, BigDecimal costThb,
                          BigDecimal price, LocalDate priceDate, BigDecimal fx, BigDecimal valueCcy,
                          BigDecimal valueThb, BigDecimal realizedThb, BigDecimal dividendsCcy,
                          BigDecimal dividendsThb, BigDecimal withholdingCcy, BigDecimal feesCcy, int trades) {

        public boolean isOpen() {
            return units.signum() > 0;
        }

        public boolean isForeign() {
            return !THB.equals(asset.getCurrency());
        }

        public BigDecimal unrealizedThb() {
            return valueThb == null ? null : valueThb.subtract(costThb);
        }

        /** Unrealized profit as a share of cost, in %. */
        public BigDecimal profitPercent() {
            BigDecimal u = unrealizedThb();
            return u == null ? null : Money.percent(u, costThb);
        }

        /** Profit in the asset currency only (no currency effect). */
        public BigDecimal unrealizedCcy() {
            return valueCcy == null ? null : valueCcy.subtract(costCcy);
        }

        public BigDecimal profitPercentCcy() {
            BigDecimal u = unrealizedCcy();
            return u == null ? null : Money.percent(u, costCcy);
        }

        /** Unrealized + realized + net dividends, in baht. */
        public BigDecimal totalReturnThb() {
            BigDecimal u = unrealizedThb();
            return u == null ? null : u.add(realizedThb).add(dividendsThb);
        }
    }

    public record Portfolio(List<Holding> holdings, BigDecimal usdThb, LocalDate usdDate) {
        public List<Holding> open() {
            return holdings.stream().filter(Holding::isOpen).toList();
        }

        public List<Holding> closed() {
            return holdings.stream().filter(h -> !h.isOpen()).toList();
        }

        /** True when a foreign holding has no USD/THB rate, so baht totals leave it out. */
        public boolean isMissingFx() {
            return holdings.stream().anyMatch(h -> h.isOpen() && h.valueThb() == null);
        }

        public BigDecimal valueThb() {
            return sum(open(), Holding::valueThb);
        }

        public BigDecimal costThb() {
            return sum(open().stream().filter(h -> h.valueThb() != null).toList(), Holding::costThb);
        }

        public BigDecimal unrealizedThb() {
            return valueThb().subtract(costThb());
        }

        public BigDecimal profitPercent() {
            return Money.percent(unrealizedThb(), costThb());
        }

        public BigDecimal realizedThb() {
            return sum(holdings, Holding::realizedThb);
        }

        public BigDecimal dividendsThb() {
            return sum(holdings, Holding::dividendsThb);
        }

        private static BigDecimal sum(List<Holding> list, java.util.function.Function<Holding, BigDecimal> f) {
            return list.stream().map(f).filter(java.util.Objects::nonNull).reduce(Money.zero(), BigDecimal::add);
        }
    }

    /** One year of the DCA projection. */
    public record ProjectionYear(int year, BigDecimal contributed, BigDecimal value) {
        public BigDecimal growth() {
            return value.subtract(contributed);
        }
    }

    private final AssetRepository assets;
    private final AssetTradeRepository trades;
    private final DividendRepository dividends;
    private final AssetPriceRepository prices;
    private final FxRateRepository fxRates;

    public PortfolioService(AssetRepository assets, AssetTradeRepository trades, DividendRepository dividends,
                            AssetPriceRepository prices, FxRateRepository fxRates) {
        this.assets = assets;
        this.trades = trades;
        this.dividends = dividends;
        this.prices = prices;
        this.fxRates = fxRates;
    }

    @Transactional(readOnly = true)
    public Portfolio portfolio() {
        Rates rates = rates();
        Map<Long, List<AssetTrade>> tradesByAsset = new HashMap<>();
        trades.findAllByOrderByTradeDateAscIdAsc()
                .forEach(t -> tradesByAsset.computeIfAbsent(t.getAsset().getId(), k -> new ArrayList<>()).add(t));
        Map<Long, List<Dividend>> divsByAsset = new HashMap<>();
        dividends.findAllByOrderByPayDateAscIdAsc()
                .forEach(d -> divsByAsset.computeIfAbsent(d.getAsset().getId(), k -> new ArrayList<>()).add(d));
        List<Holding> list = new ArrayList<>();
        for (Asset a : assets.findAllByOrderByTypeAscNameAsc()) {
            list.add(holding(a, tradesByAsset.getOrDefault(a.getId(), List.of()),
                    divsByAsset.getOrDefault(a.getId(), List.of()), rates));
        }
        FxRate usd = fxRates.findFirstByCurrencyOrderByDateDesc("USD").orElse(null);
        BigDecimal usdRate = rates.fxAt("USD", AppTime.today());
        return new Portfolio(list, usdRate, usd != null ? usd.getDate() : rates.lastTradeFxDate("USD"));
    }

    @Transactional(readOnly = true)
    public Holding holding(Asset asset) {
        return holding(asset, trades.findByAssetOrderByTradeDateAscIdAsc(asset),
                dividends.findByAssetOrderByPayDateAscIdAsc(asset), rates());
    }

    private Holding holding(Asset a, List<AssetTrade> list, List<Dividend> divs, Rates rates) {
        String ccy = a.getCurrency();
        BigDecimal units = BigDecimal.ZERO;
        BigDecimal costCcy = BigDecimal.ZERO;
        BigDecimal costThb = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        for (AssetTrade t : list) {
            BigDecimal fx = tradeFx(t, rates);
            BigDecimal fee = t.getFee() == null ? BigDecimal.ZERO : t.getFee();
            fees = fees.add(fee);
            if (t.getSide() == TradeSide.BUY) {
                BigDecimal cost = t.getAmount().add(fee);
                units = units.add(t.getUnits());
                costCcy = costCcy.add(cost);
                costThb = costThb.add(fx == null ? BigDecimal.ZERO : cost.multiply(fx));
            } else {
                BigDecimal sold = t.getUnits().min(units);
                if (units.signum() > 0 && sold.signum() > 0) {
                    BigDecimal share = sold.divide(units, MC);
                    BigDecimal removedCcy = costCcy.multiply(share);
                    BigDecimal removedThb = costThb.multiply(share);
                    BigDecimal proceeds = t.getAmount().subtract(fee);
                    realized = realized.add((fx == null ? BigDecimal.ZERO : proceeds.multiply(fx)).subtract(removedThb));
                    costCcy = costCcy.subtract(removedCcy);
                    costThb = costThb.subtract(removedThb);
                    units = units.subtract(sold);
                }
            }
        }
        if (units.signum() <= 0 || units.compareTo(new BigDecimal("0.00000001")) < 0) {
            units = BigDecimal.ZERO;
            costCcy = BigDecimal.ZERO;
            costThb = BigDecimal.ZERO;
        }
        BigDecimal divCcy = BigDecimal.ZERO;
        BigDecimal divThb = BigDecimal.ZERO;
        BigDecimal wht = BigDecimal.ZERO;
        for (Dividend d : divs) {
            BigDecimal net = d.getAmount().subtract(d.getWithholdingTax());
            BigDecimal fx = THB.equals(ccy) ? BigDecimal.ONE
                    : d.getFxRate() != null ? d.getFxRate() : rates.fxAt(ccy, d.getPayDate());
            divCcy = divCcy.add(net);
            wht = wht.add(d.getWithholdingTax());
            divThb = divThb.add(fx == null ? BigDecimal.ZERO : net.multiply(fx));
        }

        BigDecimal price = a.getCurrentPrice();
        LocalDate priceDate = a.getPriceDate();
        BigDecimal fxNow = THB.equals(ccy) ? BigDecimal.ONE : rates.fxAt(ccy, AppTime.today());
        BigDecimal valueCcy = price == null ? null : units.multiply(price);
        BigDecimal valueThb = valueCcy == null || fxNow == null ? null : valueCcy.multiply(fxNow);
        BigDecimal avg = units.signum() > 0 ? costCcy.divide(units, 6, RoundingMode.HALF_UP) : null;
        return new Holding(a, units.setScale(UNIT_SCALE, RoundingMode.HALF_UP), avg,
                Money.scale(costCcy), Money.scale(costThb), price, priceDate,
                THB.equals(ccy) ? null : fxNow, valueCcy == null ? null : Money.scale(valueCcy),
                valueThb == null ? null : Money.scale(valueThb), Money.scale(realized), Money.scale(divCcy),
                Money.scale(divThb), Money.scale(wht), Money.scale(fees), list.size());
    }

    private static BigDecimal tradeFx(AssetTrade t, Rates rates) {
        if (THB.equals(t.getCurrency())) {
            return BigDecimal.ONE;
        }
        return t.getFxRate() != null ? t.getFxRate() : rates.fxAt(t.getCurrency(), t.getTradeDate());
    }

    // ---------------------------------------------------------------- value over time

    /** Month by month from the first trade: invested cost and market value, in baht. */
    public record ValuePoint(YearMonth month, BigDecimal cost, BigDecimal value) {
    }

    @Transactional(readOnly = true)
    public List<ValuePoint> valueOverTime() {
        List<AssetTrade> all = trades.findAllByOrderByTradeDateAscIdAsc();
        List<ValuePoint> out = new ArrayList<>();
        if (all.isEmpty()) {
            return out;
        }
        Rates rates = rates();
        Map<Long, TreeMap<LocalDate, BigDecimal>> priceHistory = new HashMap<>();
        for (AssetTrade t : all) {   // trade prices are known prices too
            priceHistory.computeIfAbsent(t.getAsset().getId(), k -> new TreeMap<>()).put(t.getTradeDate(), t.getPricePerUnit());
        }
        for (AssetPrice p : prices.findAllByOrderByDateAsc()) {
            priceHistory.computeIfAbsent(p.getAsset().getId(), k -> new TreeMap<>()).put(p.getDate(), p.getPrice());
        }
        for (Asset a : assets.findAll()) {
            if (a.getCurrentPrice() != null && a.getPriceDate() != null) {
                priceHistory.computeIfAbsent(a.getId(), k -> new TreeMap<>()).putIfAbsent(a.getPriceDate(), a.getCurrentPrice());
            }
        }

        YearMonth first = YearMonth.from(all.get(0).getTradeDate());
        YearMonth last = AppTime.thisMonth();
        Map<Long, BigDecimal> units = new HashMap<>();
        Map<Long, BigDecimal> costThb = new HashMap<>();
        Map<Long, String> currency = new HashMap<>();
        int i = 0;
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate end = m.equals(last) ? AppTime.today() : m.atEndOfMonth();
            while (i < all.size() && !all.get(i).getTradeDate().isAfter(end)) {
                AssetTrade t = all.get(i++);
                Long id = t.getAsset().getId();
                currency.put(id, t.getCurrency());
                BigDecimal u = units.getOrDefault(id, BigDecimal.ZERO);
                BigDecimal c = costThb.getOrDefault(id, BigDecimal.ZERO);
                BigDecimal fx = tradeFx(t, rates);
                BigDecimal fee = t.getFee() == null ? BigDecimal.ZERO : t.getFee();
                if (t.getSide() == TradeSide.BUY) {
                    units.put(id, u.add(t.getUnits()));
                    costThb.put(id, c.add(fx == null ? BigDecimal.ZERO : t.getAmount().add(fee).multiply(fx)));
                } else if (u.signum() > 0) {
                    BigDecimal sold = t.getUnits().min(u);
                    costThb.put(id, c.subtract(c.multiply(sold.divide(u, MC))));
                    units.put(id, u.subtract(sold));
                }
            }
            BigDecimal cost = BigDecimal.ZERO;
            BigDecimal value = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> e : units.entrySet()) {
                if (e.getValue().signum() <= 0) {
                    continue;
                }
                var known = priceHistory.getOrDefault(e.getKey(), new TreeMap<>()).floorEntry(end);
                String ccy = currency.get(e.getKey());
                BigDecimal fx = THB.equals(ccy) ? BigDecimal.ONE : rates.fxAt(ccy, end);
                if (known == null || fx == null) {
                    continue;
                }
                cost = cost.add(costThb.get(e.getKey()));
                value = value.add(e.getValue().multiply(known.getValue()).multiply(fx));
            }
            out.add(new ValuePoint(m, Money.scale(cost), Money.scale(value)));
        }
        return out;
    }

    // ---------------------------------------------------------------- DCA projection

    /**
     * Investing {@code monthly} baht every month at {@code annualPercent} a year (compounded monthly, at
     * annual ÷ 12 per month), starting from {@code start}. One row per year, year 0 = today.
     */
    public static List<ProjectionYear> projection(BigDecimal start, BigDecimal monthly, BigDecimal annualPercent,
                                                  int years) {
        BigDecimal rate = annualPercent.divide(BigDecimal.valueOf(1200), MC);
        BigDecimal value = start;
        BigDecimal contributed = start;
        List<ProjectionYear> out = new ArrayList<>();
        out.add(new ProjectionYear(0, Money.scale(contributed), Money.scale(value)));
        for (int y = 1; y <= years; y++) {
            for (int m = 0; m < 12; m++) {
                value = value.multiply(BigDecimal.ONE.add(rate), MC).add(monthly);
                contributed = contributed.add(monthly);
            }
            out.add(new ProjectionYear(y, Money.scale(contributed), Money.scale(value)));
        }
        return out;
    }

    // ---------------------------------------------------------------- rates

    /** USD/THB (and other) rates known on each day: the ones typed in, plus the rates of trades. */
    private Rates rates() {
        Map<String, TreeMap<LocalDate, BigDecimal>> map = new HashMap<>();
        for (AssetTrade t : trades.findAllByOrderByTradeDateAscIdAsc()) {
            if (!THB.equals(t.getCurrency()) && t.getFxRate() != null) {
                map.computeIfAbsent(t.getCurrency(), k -> new TreeMap<>()).put(t.getTradeDate(), t.getFxRate());
            }
        }
        Map<String, LocalDate> lastTrade = new HashMap<>();
        map.forEach((c, m) -> lastTrade.put(c, m.lastKey()));
        for (FxRate r : fxRates.findAll()) {
            map.computeIfAbsent(r.getCurrency(), k -> new TreeMap<>()).put(r.getDate(), r.getRate());
        }
        return new Rates(map, lastTrade);
    }

    private record Rates(Map<String, TreeMap<LocalDate, BigDecimal>> byCurrency, Map<String, LocalDate> lastTrade) {
        /** The latest rate on or before the day; if none, the earliest known one. */
        BigDecimal fxAt(String currency, LocalDate day) {
            TreeMap<LocalDate, BigDecimal> m = byCurrency.get(currency);
            if (m == null || m.isEmpty()) {
                return null;
            }
            var e = m.floorEntry(day);
            return e != null ? e.getValue() : m.firstEntry().getValue();
        }

        LocalDate lastTradeFxDate(String currency) {
            return lastTrade.get(currency);
        }
    }
}
