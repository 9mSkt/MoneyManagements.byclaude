package com.moneymgmt.web;

import com.moneymgmt.service.IncentiveService;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;

/** The monthly incentive form. Numbers are text so "1,250" and "95.5" can be typed. */
public class IncentiveForm {

    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(1000);

    private String month;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;
    private String amount;
    private String kpiPercent;
    private String incentivePercent;
    private String bonus;
    private String note;
    private boolean existing;

    public static IncentiveForm of(YearMonth month, IncentiveService.Entry entry) {
        IncentiveForm f = new IncentiveForm();
        f.month = month.toString();
        f.date = month.equals(AppTime.thisMonth()) ? AppTime.today() : month.atDay(1);
        if (entry.incentive() != null) {
            var t = entry.incentive();
            f.existing = true;
            f.date = t.getDate();
            f.amount = t.getAmount().toPlainString();
            f.kpiPercent = plain(t.getKpiPercent());
            f.incentivePercent = plain(t.getIncentivePercent());
            f.note = t.getNote();
        }
        if (entry.bonus() != null) {
            f.existing = true;
            f.bonus = entry.bonus().getAmount().toPlainString();
        }
        return f;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /** Field → message key for every problem found; empty when the form is fine. */
    public Map<String, String> validate(java.util.function.Function<LocalDate, YearMonth> monthOf) {
        Map<String, String> errors = new LinkedHashMap<>();
        YearMonth ym = AppTime.parseMonth(month);
        BigDecimal a = Money.parse(amount);
        if (a == null || a.signum() < 0) {
            errors.put("amount", "incentive.error.amount");
        }
        if (date == null) {
            errors.put("date", "tx.error.date");
        } else if (ym == null || !monthOf.apply(date).equals(ym)) {
            errors.put("date", "incentive.error.dateMonth");
        }
        if (!percentOk(kpiPercent)) {
            errors.put("kpiPercent", "incentive.error.percent");
        }
        if (!percentOk(incentivePercent)) {
            errors.put("incentivePercent", "incentive.error.percent");
        }
        if (bonus != null && !bonus.isBlank()) {
            BigDecimal b = Money.parse(bonus);
            if (b == null || b.signum() < 0) {
                errors.put("bonus", "incentive.error.amount");
            }
        }
        return errors;
    }

    private static boolean percentOk(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        BigDecimal p = percent(text);
        return p != null && p.signum() >= 0 && p.compareTo(MAX_PERCENT) <= 0;
    }

    private static BigDecimal percent(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Money.parse(text.replace("%", ""));
    }

    /** Call only after {@link #validate()} returned no errors. */
    public IncentiveService.Input toInput() {
        return new IncentiveService.Input(yearMonth(), date, Money.parse(amount), percent(kpiPercent),
                percent(incentivePercent), Money.parse(bonus), TxForm.trimToNull(note));
    }

    public YearMonth yearMonth() {
        YearMonth ym = AppTime.parseMonth(month);
        return ym == null ? AppTime.thisMonth() : ym;
    }

    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getKpiPercent() { return kpiPercent; }
    public void setKpiPercent(String kpiPercent) { this.kpiPercent = kpiPercent; }
    public String getIncentivePercent() { return incentivePercent; }
    public void setIncentivePercent(String incentivePercent) { this.incentivePercent = incentivePercent; }
    public String getBonus() { return bonus; }
    public void setBonus(String bonus) { this.bonus = bonus; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean isExisting() { return existing; }
    public void setExisting(boolean existing) { this.existing = existing; }
}
