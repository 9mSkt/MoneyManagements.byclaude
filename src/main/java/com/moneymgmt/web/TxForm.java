package com.moneymgmt.web;

import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** What the add/edit transaction form sends. Amount is text so "1,250" can be typed. */
public class TxForm {

    private Long id;
    private TxType type = TxType.EXPENSE;
    private String amount;
    private Long categoryId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;
    private String description;
    private String note;
    private String tags;
    private String returnTo;

    public static TxForm of(Transaction t) {
        TxForm f = new TxForm();
        f.id = t.getId();
        f.type = t.getType();
        f.amount = t.getAmount() == null ? null : t.getAmount().toPlainString();
        f.categoryId = t.getCategory().getId();
        f.date = t.getDate();
        f.description = t.getDescription();
        f.note = t.getNote();
        f.tags = t.getTags();
        return f;
    }

    /** Tags cleaned up: "food, Trip ,,food" → "food, Trip". */
    public static String cleanTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String part : raw.split("[,#]")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                set.add(tag);
            }
        }
        return set.isEmpty() ? null : String.join(", ", set);
    }

    public static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public java.math.BigDecimal parsedAmount() {
        return Money.parse(amount);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public TxType getType() { return type; }
    public void setType(TxType type) { this.type = type; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getReturnTo() { return returnTo; }
    public void setReturnTo(String returnTo) { this.returnTo = returnTo; }
}
