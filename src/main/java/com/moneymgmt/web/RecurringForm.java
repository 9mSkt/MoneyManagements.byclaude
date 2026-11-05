package com.moneymgmt.web;

import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.util.Money;
import java.util.LinkedHashMap;
import java.util.Map;

/** Add / edit a fixed cost (recurring item). */
public class RecurringForm {

    private Long id;
    private String name;
    private String amount;
    private Long categoryId;
    private int dayOfMonth = 1;
    private boolean active = true;

    public static RecurringForm of(RecurringItem r) {
        RecurringForm f = new RecurringForm();
        f.id = r.getId();
        f.name = r.getName();
        f.amount = r.getAmount().toPlainString();
        f.categoryId = r.getCategory().getId();
        f.dayOfMonth = r.getDayOfMonth();
        f.active = r.isActive();
        return f;
    }

    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "fixed.error.name");
        } else if (name.trim().length() > 200) {
            errors.put("name", "fixed.error.tooLong");
        }
        var a = Money.parse(amount);
        if (a == null || a.signum() <= 0) {
            errors.put("amount", "tx.error.amount");
        }
        if (categoryId == null) {
            errors.put("categoryId", "tx.error.category");
        }
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            errors.put("dayOfMonth", "fixed.error.day");
        }
        return errors;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public int getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(int dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
