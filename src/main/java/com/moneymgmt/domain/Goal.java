package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "goal")
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "saved_amount", nullable = false)
    private BigDecimal savedAmount = BigDecimal.ZERO;

    private LocalDate deadline;

    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    protected Goal() {
    }

    public Goal(String name, BigDecimal targetAmount, BigDecimal savedAmount, LocalDate deadline) {
        this.name = name;
        this.targetAmount = targetAmount;
        this.savedAmount = savedAmount;
        this.deadline = deadline;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }
    public BigDecimal getSavedAmount() { return savedAmount; }
    public void setSavedAmount(BigDecimal savedAmount) { this.savedAmount = savedAmount; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }

    /** Still to save (never below 0). */
    public BigDecimal getRemaining() {
        return com.moneymgmt.util.Money.scale(targetAmount.subtract(savedAmount).max(BigDecimal.ZERO));
    }

    public boolean isReached() {
        return savedAmount.compareTo(targetAmount) >= 0;
    }

    /** Saved as a % of the target, 0 to 100 (for the progress bar). */
    public BigDecimal getProgressPercent() {
        BigDecimal p = com.moneymgmt.util.Money.percent(savedAmount, targetAmount);
        return p.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }

    /** Whole months from this month up to and including the deadline month; null without a deadline. */
    public Integer monthsLeft(java.time.LocalDate today) {
        if (deadline == null) {
            return null;
        }
        long m = java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(today),
                java.time.YearMonth.from(deadline)) + 1;
        return (int) Math.max(0, m);
    }

    /** What to put aside each month to reach the target by the deadline; null without a deadline or when reached. */
    public BigDecimal monthlyNeeded(java.time.LocalDate today) {
        Integer left = monthsLeft(today);
        if (left == null || isReached()) {
            return null;
        }
        if (left == 0) {
            return getRemaining();
        }
        return getRemaining().divide(BigDecimal.valueOf(left), 2, java.math.RoundingMode.HALF_UP);
    }

    /** Deadline passed and the target is not reached. */
    public boolean isLate(java.time.LocalDate today) {
        return deadline != null && deadline.isBefore(today) && !isReached();
    }
}
