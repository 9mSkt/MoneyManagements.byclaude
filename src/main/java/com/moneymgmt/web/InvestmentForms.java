package com.moneymgmt.web;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetTrade;
import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.Dividend;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.service.InvestmentService.TradeInput;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;

/** Form objects of the investment pages. Numbers are text so "1,234.56" can be typed. */
public final class InvestmentForms {

    private InvestmentForms() {
    }

    /** A number with many decimals (units, NAV, price, rate); null when empty or not a number. */
    static BigDecimal decimal(String text, int scale) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").replace("฿", "").replace("$", "").trim())
                    .setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    public static class AssetForm {
        private Long id;
        private String name;
        private AssetType type = AssetType.MUTUAL_FUND;
        private String currency = "THB";
        private String price;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate priceDate = AppTime.today();

        public static AssetForm of(Asset a) {
            AssetForm f = new AssetForm();
            f.id = a.getId();
            f.name = a.getName();
            f.type = a.getType();
            f.currency = a.getCurrency();
            f.price = plain(a.getCurrentPrice());
            f.priceDate = a.getPriceDate() == null ? AppTime.today() : a.getPriceDate();
            return f;
        }

        public Map<String, String> validate() {
            Map<String, String> e = new LinkedHashMap<>();
            if (name == null || name.isBlank()) {
                e.put("name", "fixed.error.name");
            } else if (name.trim().length() > 100) {
                e.put("name", "fixed.error.tooLong");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                e.put("currency", "inv.error.currency");
            }
            if (price != null && !price.isBlank()) {
                BigDecimal p = decimal(price, 6);
                if (p == null || p.signum() <= 0) {
                    e.put("price", "inv.error.price");
                }
            }
            return e;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public AssetType getType() { return type; }
        public void setType(AssetType type) { this.type = type; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency == null ? null : currency.trim().toUpperCase(); }
        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }
        public LocalDate getPriceDate() { return priceDate; }
        public void setPriceDate(LocalDate priceDate) { this.priceDate = priceDate; }
    }

    /**
     * A buy or sell. Type any two of units, price and amount; the third is worked out
     * (amount = units × price, before the fee).
     */
    public static class TradeForm {
        private Long entryId;
        private TradeSide side = TradeSide.BUY;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date = AppTime.today();
        private String units;
        private String price;
        private String amount;
        private String fee;
        private String fxRate;
        private boolean addTransaction = true;

        public static TradeForm of(AssetTrade t) {
            TradeForm f = new TradeForm();
            f.entryId = t.getId();
            f.side = t.getSide();
            f.date = t.getTradeDate();
            f.units = plain(t.getUnits());
            f.price = plain(t.getPricePerUnit());
            f.amount = plain(t.getAmount());
            f.fee = plain(t.getFee());
            f.fxRate = plain(t.getFxRate());
            f.addTransaction = false;
            return f;
        }

        /** Checks the form; on success {@code out[0]} holds the values to save. */
        public Map<String, String> validate(boolean foreign, TradeInput[] out) {
            Map<String, String> e = new LinkedHashMap<>();
            BigDecimal u = decimal(units, 8);
            BigDecimal p = decimal(price, 6);
            BigDecimal a = Money.parse(amount);
            if (date == null) {
                e.put("date", "tx.error.date");
            }
            if (u != null && u.signum() <= 0) {
                e.put("units", "inv.error.units");
            }
            if (p != null && p.signum() <= 0) {
                e.put("price", "inv.error.price");
            }
            if (a != null && a.signum() <= 0) {
                e.put("amount", "tx.error.amount");
            }
            if (e.isEmpty()) {
                if (u != null && p != null && a == null) {
                    a = Money.scale(u.multiply(p));
                } else if (u == null && p != null && a != null) {
                    u = a.divide(p, 8, RoundingMode.HALF_UP);
                } else if (u != null && p == null && a != null) {
                    p = a.divide(u, 6, RoundingMode.HALF_UP);
                } else if (u == null || p == null) {
                    e.put("units", "inv.error.twoOfThree");
                }
            }
            BigDecimal f = fee == null || fee.isBlank() ? Money.zero() : Money.parse(fee);
            if (f == null || f.signum() < 0) {
                e.put("fee", "tx.error.fee");
            }
            BigDecimal fx = decimal(fxRate, 6);
            if (foreign && (fx == null || fx.signum() <= 0)) {
                e.put("fxRate", "inv.error.fx");
            }
            if (e.isEmpty()) {
                out[0] = new TradeInput(date, side, u, p, a, f, foreign ? fx : null);
            }
            return e;
        }

        public Long getEntryId() { return entryId; }
        public void setEntryId(Long entryId) { this.entryId = entryId; }
        public TradeSide getSide() { return side; }
        public void setSide(TradeSide side) { this.side = side; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public String getUnits() { return units; }
        public void setUnits(String units) { this.units = units; }
        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        public String getFee() { return fee; }
        public void setFee(String fee) { this.fee = fee; }
        public String getFxRate() { return fxRate; }
        public void setFxRate(String fxRate) { this.fxRate = fxRate; }
        public boolean isAddTransaction() { return addTransaction; }
        public void setAddTransaction(boolean addTransaction) { this.addTransaction = addTransaction; }
    }

    public static class DividendForm {
        private Long entryId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date = AppTime.today();
        private String amount;
        private String withholding;
        private String fxRate;

        public static DividendForm of(Dividend d) {
            DividendForm f = new DividendForm();
            f.entryId = d.getId();
            f.date = d.getPayDate();
            f.amount = plain(d.getAmount());
            f.withholding = plain(d.getWithholdingTax());
            f.fxRate = plain(d.getFxRate());
            return f;
        }

        public Map<String, String> validate() {
            Map<String, String> e = new LinkedHashMap<>();
            if (date == null) {
                e.put("date", "tx.error.date");
            }
            BigDecimal a = Money.parse(amount);
            if (a == null || a.signum() <= 0) {
                e.put("amount", "tx.error.amount");
            }
            BigDecimal w = withholdingValue();
            if (w == null || w.signum() < 0 || (a != null && w.compareTo(a) > 0)) {
                e.put("withholding", "inv.error.withholding");
            }
            if (fxRate != null && !fxRate.isBlank()) {
                BigDecimal fx = decimal(fxRate, 6);
                if (fx == null || fx.signum() <= 0) {
                    e.put("fxRate", "inv.error.fx");
                }
            }
            return e;
        }

        public BigDecimal withholdingValue() {
            return withholding == null || withholding.isBlank() ? Money.zero() : Money.parse(withholding);
        }

        public Long getEntryId() { return entryId; }
        public void setEntryId(Long entryId) { this.entryId = entryId; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        public String getWithholding() { return withholding; }
        public void setWithholding(String withholding) { this.withholding = withholding; }
        public String getFxRate() { return fxRate; }
        public void setFxRate(String fxRate) { this.fxRate = fxRate; }
    }
}
