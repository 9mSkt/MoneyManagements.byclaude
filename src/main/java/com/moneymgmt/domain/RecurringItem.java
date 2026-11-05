package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "recurring_item")
public class RecurringItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "day_of_month", nullable = false)
    private int dayOfMonth = 1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(nullable = false)
    private boolean active = true;

    /** First day of the last month the app posted this item; a deleted posting is not posted again. */
    @Column(name = "last_posted_month")
    private java.time.LocalDate lastPostedMonth;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    protected RecurringItem() {
    }

    public RecurringItem(String name, BigDecimal amount, Category category) {
        this.name = name;
        this.amount = amount;
        this.category = category;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public int getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(int dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public java.time.LocalDate getLastPostedMonth() { return lastPostedMonth; }
    public void setLastPostedMonth(java.time.LocalDate lastPostedMonth) { this.lastPostedMonth = lastPostedMonth; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
}
