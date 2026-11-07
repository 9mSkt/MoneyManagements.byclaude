package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Add / edit a category: names in both languages, budget group, order and on/off. The type is fixed once saved. */
public class CategoryForm {

    private Long id;
    private String nameEn;
    private String nameTh;
    private TxType type = TxType.EXPENSE;
    private BudgetGroup budgetGroup = BudgetGroup.NECESSARY;
    private int sortOrder = 500;
    private boolean active = true;

    public static CategoryForm of(Category c) {
        CategoryForm f = new CategoryForm();
        f.id = c.getId();
        f.nameEn = c.getNameEn();
        f.nameTh = c.getNameTh();
        f.type = c.getType();
        f.budgetGroup = c.getBudgetGroup();
        f.sortOrder = c.getSortOrder();
        f.active = c.isActive();
        return f;
    }

    /** Budget groups a category of this type can belong to (income has none). */
    public static List<BudgetGroup> groupsFor(TxType type) {
        return switch (type) {
            case INCOME -> List.of();
            case EXPENSE -> List.of(BudgetGroup.FIXED, BudgetGroup.GAS, BudgetGroup.NECESSARY, BudgetGroup.SHOPPING,
                    BudgetGroup.EAT);
            case TRANSFER -> List.of(BudgetGroup.SAVING, BudgetGroup.INVESTMENT);
        };
    }

    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (nameEn == null || nameEn.isBlank()) {
            errors.put("nameEn", "cat.error.name");
        } else if (nameEn.trim().length() > 100) {
            errors.put("nameEn", "fixed.error.tooLong");
        }
        if (nameTh == null || nameTh.isBlank()) {
            errors.put("nameTh", "cat.error.name");
        } else if (nameTh.trim().length() > 100) {
            errors.put("nameTh", "fixed.error.tooLong");
        }
        if (type == null) {
            errors.put("type", "form.error.invalid");
        } else if (type == TxType.INCOME) {
            budgetGroup = null;
        } else if (budgetGroup == null || !groupsFor(type).contains(budgetGroup)) {
            errors.put("budgetGroup", "cat.error.group");
        }
        if (sortOrder < 0 || sortOrder > 99999) {
            errors.put("sortOrder", "form.error.invalid");
        }
        return errors;
    }

    /** A code for a new category from its English name: "Pet food" → "PET_FOOD". */
    public String suggestedCode() {
        String base = nameEn == null ? "" : nameEn.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "CATEGORY";
        }
        return base.length() > 50 ? base.substring(0, 50) : base;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getNameTh() { return nameTh; }
    public void setNameTh(String nameTh) { this.nameTh = nameTh; }
    public TxType getType() { return type; }
    public void setType(TxType type) { this.type = type; }
    public BudgetGroup getBudgetGroup() { return budgetGroup; }
    public void setBudgetGroup(BudgetGroup budgetGroup) { this.budgetGroup = budgetGroup; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
