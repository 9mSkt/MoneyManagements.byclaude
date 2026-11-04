package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "installment")
public class Installment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String item;

    @Column(name = "full_price", nullable = false)
    private BigDecimal fullPrice;

    @Column(name = "monthly_amount", nullable = false)
    private BigDecimal monthlyAmount;

    @Column(nullable = false)
    private int months;

    @Column(name = "interest_percent", nullable = false)
    private BigDecimal interestPercent = BigDecimal.ZERO;

    /** First day of the month in which payment 1 was made. */
    @Column(name = "start_month", nullable = false)
    private LocalDate startMonth;

    @Column(name = "paid_count", nullable = false)
    private int paidCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    protected Installment() {
    }

    public Installment(String item, BigDecimal fullPrice, BigDecimal monthlyAmount, int months, LocalDate startMonth) {
        this.item = item;
        this.fullPrice = fullPrice;
        this.monthlyAmount = monthlyAmount;
        this.months = months;
        this.startMonth = startMonth;
    }

    public Long getId() { return id; }
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public BigDecimal getFullPrice() { return fullPrice; }
    public void setFullPrice(BigDecimal fullPrice) { this.fullPrice = fullPrice; }
    public BigDecimal getMonthlyAmount() { return monthlyAmount; }
    public void setMonthlyAmount(BigDecimal monthlyAmount) { this.monthlyAmount = monthlyAmount; }
    public int getMonths() { return months; }
    public void setMonths(int months) { this.months = months; }
    public BigDecimal getInterestPercent() { return interestPercent; }
    public void setInterestPercent(BigDecimal interestPercent) { this.interestPercent = interestPercent; }
    public LocalDate getStartMonth() { return startMonth; }
    public void setStartMonth(LocalDate startMonth) { this.startMonth = startMonth; }
    public int getPaidCount() { return paidCount; }
    public void setPaidCount(int paidCount) { this.paidCount = paidCount; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
}
