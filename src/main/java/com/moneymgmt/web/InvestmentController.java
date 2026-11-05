package com.moneymgmt.web;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetTrade;
import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.Dividend;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.repo.AssetPriceRepository;
import com.moneymgmt.repo.AssetRepository;
import com.moneymgmt.repo.AssetTradeRepository;
import com.moneymgmt.repo.DividendRepository;
import com.moneymgmt.repo.FxRateRepository;
import com.moneymgmt.service.InvestmentService;
import com.moneymgmt.service.InvestmentService.TradeInput;
import com.moneymgmt.service.PortfolioService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import com.moneymgmt.web.InvestmentForms.AssetForm;
import com.moneymgmt.web.InvestmentForms.DividendForm;
import com.moneymgmt.web.InvestmentForms.TradeForm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Investment portfolio: holdings, one page per fund or ETF, trades, dividends, prices and the DCA projection. */
@Controller
public class InvestmentController {

    /** Defaults from the design: 7,000 baht a month at 10 % a year. */
    static final BigDecimal DCA_MONTHLY = Money.of("7000");
    static final BigDecimal DCA_RATE = new BigDecimal("10");
    static final int DCA_YEARS = 20;

    /** Terms explained on the portfolio page (from the glossary on the sheet's Investment tab). */
    static final List<String> GLOSSARY = List.of("revenue", "grossProfit", "operatingIncome", "netIncome", "eps",
            "longTermLiabilities", "roe", "equity", "nav", "dca", "withholding", "averageCost");

    private final PortfolioService portfolio;
    private final InvestmentService service;
    private final AssetRepository assets;
    private final AssetTradeRepository trades;
    private final DividendRepository dividends;
    private final AssetPriceRepository prices;
    private final FxRateRepository fxRates;
    private final MessageSource messages;

    public InvestmentController(PortfolioService portfolio, InvestmentService service, AssetRepository assets,
                                AssetTradeRepository trades, DividendRepository dividends,
                                AssetPriceRepository prices, FxRateRepository fxRates, MessageSource messages) {
        this.portfolio = portfolio;
        this.service = service;
        this.assets = assets;
        this.trades = trades;
        this.dividends = dividends;
        this.prices = prices;
        this.fxRates = fxRates;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- portfolio

    @GetMapping("/investments")
    public String index(Model model) {
        var p = portfolio.portfolio();
        model.addAttribute("p", p);
        model.addAttribute("dcaMonthly", DCA_MONTHLY);
        model.addAttribute("dcaRate", DCA_RATE);
        model.addAttribute("dcaYears", DCA_YEARS);
        model.addAttribute("glossary", GLOSSARY);
        return "investments/index";
    }

    /** Allocation (by asset and by type) and value over time, in baht. */
    @GetMapping("/api/investments/charts")
    @ResponseBody
    public Map<String, Object> charts(Locale locale) {
        var p = portfolio.portfolio();
        List<Map<String, Object>> byAsset = new ArrayList<>();
        Map<AssetType, BigDecimal> byType = new LinkedHashMap<>();
        for (var h : p.open()) {
            if (h.valueThb() != null && h.valueThb().signum() > 0) {
                byAsset.add(Map.of("name", h.asset().getName(), "type", h.asset().getType().name(),
                        "value", h.valueThb()));
                byType.merge(h.asset().getType(), h.valueThb(), BigDecimal::add);
            }
        }
        List<Map<String, Object>> types = new ArrayList<>();
        byType.forEach((t, v) -> types.add(Map.of("key", t.name(), "name", msg("inv.type." + t, locale), "value", v)));

        List<Map<String, Integer>> months = new ArrayList<>();
        List<BigDecimal> cost = new ArrayList<>();
        List<BigDecimal> value = new ArrayList<>();
        for (var pt : portfolio.valueOverTime()) {
            months.add(Map.of("y", pt.month().getYear(), "m", pt.month().getMonthValue()));
            cost.add(pt.cost());
            value.add(pt.value());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", Map.of("cost", msg("inv.chart.cost", locale), "value", msg("inv.chart.value", locale),
                "contributed", msg("inv.chart.contributed", locale), "growth", msg("inv.chart.growth", locale),
                "year", msg("inv.chart.year", locale), "noData", msg("inv.chart.noData", locale)));
        data.put("byAsset", byAsset);
        data.put("byType", types);
        data.put("history", Map.of("months", months, "cost", cost, "value", value));
        return data;
    }

    /** DCA projection, worked out on the server with exact decimals. */
    @GetMapping("/api/investments/projection")
    @ResponseBody
    public Map<String, Object> projection(@RequestParam(required = false) String monthly,
                                          @RequestParam(required = false) String rate,
                                          @RequestParam(required = false) Integer years,
                                          @RequestParam(required = false) String start) {
        BigDecimal m = clamp(Money.parse(monthly), DCA_MONTHLY, Money.zero(), Money.of("10000000"));
        BigDecimal r = clamp(Money.parse(rate), DCA_RATE, new BigDecimal("-50"), new BigDecimal("50"));
        int y = years == null ? DCA_YEARS : Math.max(1, Math.min(years, 50));
        BigDecimal s = Money.parse(start);
        if (s == null || s.signum() < 0) {
            s = portfolio.portfolio().valueThb();
        }
        var rows = PortfolioService.projection(s, m, r, y);
        var last = rows.get(rows.size() - 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("monthly", m);
        data.put("rate", r);
        data.put("years", y);
        data.put("start", s);
        data.put("rows", rows.stream().map(x -> Map.of("year", x.year(), "contributed", x.contributed(),
                "value", x.value())).toList());
        data.put("final", Map.of("contributed", last.contributed(), "value", last.value(), "growth", last.growth()));
        return data;
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal fallback, BigDecimal min, BigDecimal max) {
        if (v == null) {
            return fallback;
        }
        return v.max(min).min(max);
    }

    // ---------------------------------------------------------------- one asset

    @GetMapping("/investments/{id}")
    public String asset(@PathVariable Long id, Model model) {
        Asset a = find(id);
        List<AssetTrade> t = new ArrayList<>(trades.findByAssetOrderByTradeDateAscIdAsc(a));
        Collections.reverse(t);
        List<Dividend> d = new ArrayList<>(dividends.findByAssetOrderByPayDateAscIdAsc(a));
        Collections.reverse(d);
        var ph = new ArrayList<>(prices.findByAssetOrderByDateAsc(a));
        Collections.reverse(ph);
        model.addAttribute("asset", a);
        model.addAttribute("h", portfolio.holding(a));
        model.addAttribute("trades", t);
        model.addAttribute("dividends", d);
        model.addAttribute("priceHistory", ph.size() > 24 ? ph.subList(0, 24) : ph);
        return "investments/asset";
    }

    @GetMapping("/investments/assets/new")
    public String newAsset(Model model) {
        return assetForm(new AssetForm(), Map.of(), model);
    }

    @GetMapping("/investments/{id}/edit")
    public String editAsset(@PathVariable Long id, Model model) {
        return assetForm(AssetForm.of(find(id)), Map.of(), model);
    }

    @PostMapping("/investments/assets")
    public String saveAsset(@ModelAttribute("form") AssetForm f, BindingResult binding, Model model,
                            RedirectAttributes flash) {
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        if (!errors.isEmpty()) {
            return assetForm(f, errors, model);
        }
        Asset a = f.getId() == null ? new Asset(f.getName().trim(), f.getType(), f.getCurrency()) : find(f.getId());
        a.setName(f.getName().trim());
        a.setType(f.getType());
        a.setCurrency(f.getCurrency());
        BigDecimal price = InvestmentForms.decimal(f.getPrice(), 6);
        assets.save(a);
        if (price != null) {
            service.updatePrices(f.getPriceDate() == null ? AppTime.today() : f.getPriceDate(), Map.of(a.getId(), price), null);
        }
        flash.addFlashAttribute("flash", "inv.assetSaved");
        return "redirect:/investments/" + a.getId();
    }

    @PostMapping("/investments/{id}/delete")
    public String deleteAsset(@PathVariable Long id, RedirectAttributes flash) {
        assets.delete(find(id));
        flash.addFlashAttribute("flash", "inv.assetDeleted");
        return "redirect:/investments";
    }

    private String assetForm(AssetForm f, Map<String, String> errors, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        model.addAttribute("types", AssetType.values());
        return "investments/asset-form";
    }

    // ---------------------------------------------------------------- trades

    @GetMapping("/investments/{id}/trades/new")
    public String newTrade(@PathVariable Long id, @RequestParam(required = false) TradeSide side, Model model) {
        Asset a = find(id);
        TradeForm f = new TradeForm();
        if (side != null) {
            f.setSide(side);
        }
        if (a.getCurrentPrice() != null) {
            f.setPrice(InvestmentForms.plain(a.getCurrentPrice()));
        }
        if (!PortfolioService.THB.equals(a.getCurrency())) {
            BigDecimal fx = portfolio.portfolio().usdThb();
            f.setFxRate(InvestmentForms.plain(fx));
        }
        return tradeForm(a, f, Map.of(), model);
    }

    @GetMapping("/investments/{id}/trades/{tradeId}/edit")
    public String editTrade(@PathVariable Long id, @PathVariable Long tradeId, Model model) {
        Asset a = find(id);
        AssetTrade t = trades.findById(tradeId).filter(x -> x.getAsset().getId().equals(id))
                .orElseThrow(TransactionController::notFound);
        return tradeForm(a, TradeForm.of(t), Map.of(), model);
    }

    @PostMapping("/investments/{id}/trades")
    public String saveTrade(@PathVariable Long id, @ModelAttribute("form") TradeForm f, BindingResult binding,
                            Model model, RedirectAttributes flash) {
        Asset a = find(id);
        TradeInput[] out = new TradeInput[1];
        Map<String, String> errors = FixedCostController.withBindingErrors(binding,
                f.validate(!PortfolioService.THB.equals(a.getCurrency()), out));
        if (!errors.isEmpty()) {
            return tradeForm(a, f, errors, model);
        }
        service.saveTrade(a, f.getEntryId(), out[0], f.isAddTransaction());
        flash.addFlashAttribute("flash", f.getEntryId() == null && f.isAddTransaction() && f.getSide() == TradeSide.BUY
                ? "inv.tradeSavedWithTx" : "inv.tradeSaved");
        return "redirect:/investments/" + id;
    }

    @PostMapping("/investments/{id}/trades/{tradeId}/delete")
    public String deleteTrade(@PathVariable Long id, @PathVariable Long tradeId, RedirectAttributes flash) {
        trades.findById(tradeId).filter(x -> x.getAsset().getId().equals(id)).ifPresent(trades::delete);
        flash.addFlashAttribute("flash", "inv.tradeDeleted");
        return "redirect:/investments/" + id;
    }

    private String tradeForm(Asset a, TradeForm f, Map<String, String> errors, Model model) {
        model.addAttribute("asset", a);
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        return "investments/trade-form";
    }

    // ---------------------------------------------------------------- dividends

    @GetMapping("/investments/{id}/dividends/new")
    public String newDividend(@PathVariable Long id, Model model) {
        Asset a = find(id);
        DividendForm f = new DividendForm();
        if (!PortfolioService.THB.equals(a.getCurrency())) {
            f.setFxRate(InvestmentForms.plain(portfolio.portfolio().usdThb()));
        }
        return dividendForm(a, f, Map.of(), model);
    }

    @GetMapping("/investments/{id}/dividends/{divId}/edit")
    public String editDividend(@PathVariable Long id, @PathVariable Long divId, Model model) {
        Asset a = find(id);
        Dividend d = dividends.findById(divId).filter(x -> x.getAsset().getId().equals(id))
                .orElseThrow(TransactionController::notFound);
        return dividendForm(a, DividendForm.of(d), Map.of(), model);
    }

    @PostMapping("/investments/{id}/dividends")
    public String saveDividend(@PathVariable Long id, @ModelAttribute("form") DividendForm f, BindingResult binding,
                               Model model, RedirectAttributes flash) {
        Asset a = find(id);
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        if (!errors.isEmpty()) {
            return dividendForm(a, f, errors, model);
        }
        BigDecimal fx = PortfolioService.THB.equals(a.getCurrency()) ? null : InvestmentForms.decimal(f.getFxRate(), 6);
        service.saveDividend(a, f.getEntryId(), f.getDate(), Money.parse(f.getAmount()), f.withholdingValue(), fx);
        flash.addFlashAttribute("flash", "inv.dividendSaved");
        return "redirect:/investments/" + id;
    }

    @PostMapping("/investments/{id}/dividends/{divId}/delete")
    public String deleteDividend(@PathVariable Long id, @PathVariable Long divId, RedirectAttributes flash) {
        dividends.findById(divId).filter(x -> x.getAsset().getId().equals(id)).ifPresent(dividends::delete);
        flash.addFlashAttribute("flash", "inv.dividendDeleted");
        return "redirect:/investments/" + id;
    }

    private String dividendForm(Asset a, DividendForm f, Map<String, String> errors, Model model) {
        model.addAttribute("asset", a);
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        return "investments/dividend-form";
    }

    // ---------------------------------------------------------------- prices

    @GetMapping("/investments/prices")
    public String pricesForm(Model model) {
        var p = portfolio.portfolio();
        model.addAttribute("p", p);
        model.addAttribute("date", AppTime.today());
        model.addAttribute("errors", Map.of());
        model.addAttribute("values", Map.of());
        return "investments/prices";
    }

    /** Fields: date, usd, price_{assetId}. Empty price fields are left as they are. */
    @PostMapping("/investments/prices")
    public String savePrices(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam Map<String, String> params, Model model, RedirectAttributes flash) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<Long, BigDecimal> newPrices = new LinkedHashMap<>();
        for (var e : params.entrySet()) {
            if (e.getKey().startsWith("price_") && !e.getValue().isBlank()) {
                BigDecimal v = InvestmentForms.decimal(e.getValue(), 6);
                if (v == null || v.signum() <= 0) {
                    errors.put(e.getKey(), "inv.error.price");
                } else {
                    try {
                        newPrices.put(Long.valueOf(e.getKey().substring(6)), v);
                    } catch (NumberFormatException ex) {
                        errors.put(e.getKey(), "form.error.invalid");
                    }
                }
            }
        }
        BigDecimal usd = null;
        String usdText = params.getOrDefault("usd", "");
        if (!usdText.isBlank()) {
            usd = InvestmentForms.decimal(usdText, 6);
            if (usd == null || usd.signum() <= 0) {
                errors.put("usd", "inv.error.fx");
            }
        }
        if (date == null) {
            errors.put("date", "tx.error.date");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("p", portfolio.portfolio());
            model.addAttribute("date", date == null ? AppTime.today() : date);
            model.addAttribute("errors", errors);
            model.addAttribute("values", params);
            return "investments/prices";
        }
        service.updatePrices(date, newPrices, usd);
        flash.addFlashAttribute("flash", "inv.pricesSaved");
        return "redirect:/investments";
    }

    // ---------------------------------------------------------------------------------------------

    private Asset find(Long id) {
        return assets.findById(id).orElseThrow(TransactionController::notFound);
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    /** Latest USD/THB typed in, for showing next to the prices form (null if none). */
    @ModelAttribute("usdTyped")
    public com.moneymgmt.domain.FxRate usdTyped() {
        return fxRates.findFirstByCurrencyOrderByDateDesc("USD").orElse(null);
    }
}
