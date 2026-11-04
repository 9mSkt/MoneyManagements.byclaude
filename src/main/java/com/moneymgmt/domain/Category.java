package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.util.Locale;

@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_th", nullable = false)
    private String nameTh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_group")
    private BudgetGroup budgetGroup;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(nullable = false)
    private boolean active = true;

    protected Category() {
    }

    public Category(String code, String nameEn, String nameTh, TxType type, BudgetGroup budgetGroup) {
        this.code = code;
        this.nameEn = nameEn;
        this.nameTh = nameTh;
        this.type = type;
        this.budgetGroup = budgetGroup;
    }

    /** Name in the user's language (Thai for "th", English otherwise). */
    public String name(Locale locale) {
        return locale != null && "th".equals(locale.getLanguage()) ? nameTh : nameEn;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getNameTh() { return nameTh; }
    public void setNameTh(String nameTh) { this.nameTh = nameTh; }
    public TxType getType() { return type; }
    public BudgetGroup getBudgetGroup() { return budgetGroup; }
    public void setBudgetGroup(BudgetGroup budgetGroup) { this.budgetGroup = budgetGroup; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
