package com.moneymgmt.web;

import com.moneymgmt.domain.Installment;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/** Add / edit an installment plan (ผ่อน). */
public class InstallmentForm {

    private Long id;
    private String item;
    private Long categoryId;
    private String fullPrice;
    private int months = 10;
    private String interestPercent = "0";
    private String startMonth;
    private int paidCount;
    /** Optional: type the monthly charge from the card statement; empty = calculate. */
    private String monthlyAmount;

    public static InstallmentForm blank() {
        InstallmentForm f = new InstallmentForm();
        f.startMonth = AppTime.thisMonth().toString();
        return f;
    }

    public static InstallmentForm of(Installment i) {
        InstallmentForm f = new InstallmentForm();
        f.id = i.getId();
        f.item = i.getItem();
        f.categoryId = i.getCategory() == null ? null : i.getCategory().getId();
        f.fullPrice = i.getFullPrice().toPlainString();
        f.months = i.getMonths();
        f.interestPercent = i.getInterestPercent().stripTrailingZeros().toPlainString();
        f.startMonth = YearMonth.from(i.getStartMonth()).toString();
        f.paidCount = i.getPaidCount();
        BigDecimal calculated = Installment.monthlyCharge(i.getFullPrice(), i.getMonths(), i.getInterestPercent());
        if (calculated.compareTo(i.getMonthlyAmount()) != 0) {
            f.monthlyAmount = i.getMonthlyAmount().toPlainString();
        }
        return f;
    }

    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (item == null || item.isBlank()) {
            errors.put("item", "fixed.error.name");
        } else if (item.trim().length() > 200) {
            errors.put("item", "fixed.error.tooLong");
        }
        if (categoryId == null) {
            errors.put("categoryId", "tx.error.category");
        }
        BigDecimal full = Money.parse(fullPrice);
        if (full == null || full.signum() <= 0) {
            errors.put("fullPrice", "tx.error.amount");
        }
        if (months < 1 || months > 120) {
            errors.put("months", "inst.error.months");
        }
        BigDecimal rate = rate();
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            errors.put("interestPercent", "incentive.error.percent");
        }
        if (AppTime.parseMonth(startMonth) == null) {
            errors.put("startMonth", "inst.error.start");
        }
        if (paidCount < 0 || paidCount > months) {
            errors.put("paidCount", "inst.error.paid");
        }
        if (monthlyAmount != null && !monthlyAmount.isBlank()) {
            BigDecimal m = Money.parse(monthlyAmount);
            if (m == null || m.signum() <= 0) {
                errors.put("monthlyAmount", "tx.error.amount");
            }
        }
        return errors;
    }

    /** Interest % per month as a number (4 decimals); "0" when empty. */
    public BigDecimal rate() {
        if (interestPercent == null || interestPercent.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(interestPercent.replace("%", "").replace(",", "").trim())
                    .setScale(4, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The monthly charge to store: the typed one, or full price ÷ months + flat interest. */
    public BigDecimal resolvedMonthly() {
        BigDecimal typed = Money.parse(monthlyAmount);
        return typed != null ? typed : Installment.monthlyCharge(Money.parse(fullPrice), months, rate());
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getFullPrice() { return fullPrice; }
    public void setFullPrice(String fullPrice) { this.fullPrice = fullPrice; }
    public int getMonths() { return months; }
    public void setMonths(int months) { this.months = months; }
    public String getInterestPercent() { return interestPercent; }
    public void setInterestPercent(String interestPercent) { this.interestPercent = interestPercent; }
    public String getStartMonth() { return startMonth; }
    public void setStartMonth(String startMonth) { this.startMonth = startMonth; }
    public int getPaidCount() { return paidCount; }
    public void setPaidCount(int paidCount) { this.paidCount = paidCount; }
    public String getMonthlyAmount() { return monthlyAmount; }
    public void setMonthlyAmount(String monthlyAmount) { this.monthlyAmount = monthlyAmount; }
}
