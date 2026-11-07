package com.moneymgmt.web;

import com.moneymgmt.domain.Goal;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Add / edit a saving goal. */
public class GoalForm {

    private Long id;
    private String name;
    private String target;
    private String saved = "0";
    private String deadline;
    private String note;

    public static GoalForm of(Goal g) {
        GoalForm f = new GoalForm();
        f.id = g.getId();
        f.name = g.getName();
        f.target = g.getTargetAmount().toPlainString();
        f.saved = g.getSavedAmount().toPlainString();
        f.deadline = g.getDeadline() == null ? null : g.getDeadline().toString();
        f.note = g.getNote();
        return f;
    }

    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "goal.error.name");
        } else if (name.trim().length() > 200) {
            errors.put("name", "fixed.error.tooLong");
        }
        BigDecimal t = Money.parse(target);
        if (t == null || t.signum() <= 0) {
            errors.put("target", "tx.error.amount");
        }
        BigDecimal s = Money.parse(saved == null || saved.isBlank() ? "0" : saved);
        if (s == null || s.signum() < 0) {
            errors.put("saved", "goal.error.saved");
        }
        if (deadline != null && !deadline.isBlank() && parsedDeadline() == null) {
            errors.put("deadline", "form.error.invalid");
        }
        if (note != null && note.length() > 500) {
            errors.put("note", "fixed.error.tooLong");
        }
        return errors;
    }

    public BigDecimal targetAmount() {
        return Money.parse(target);
    }

    public BigDecimal savedAmount() {
        BigDecimal s = Money.parse(saved);
        return s == null ? Money.zero() : s;
    }

    public LocalDate parsedDeadline() {
        if (deadline == null || deadline.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(deadline.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public String cleanNote() {
        return note == null || note.isBlank() ? null : note.trim();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getSaved() { return saved; }
    public void setSaved(String saved) { this.saved = saved; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
