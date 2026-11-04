package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Budget for one group, as a percent of income after fixed cost or a fixed baht amount, from a month onward. */
@Entity
@Table(name = "budget_rule")
public class BudgetRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_month", nullable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_group", nullable = false)
    private BudgetGroup group;

    private BigDecimal percent;

    @Column(name = "fixed_amount")
    private BigDecimal fixedAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    protected BudgetRule() {
    }

    public BudgetRule(LocalDate month, BudgetKind kind, BudgetGroup group, BigDecimal percent, BigDecimal fixedAmount) {
        this.month = month;
        this.kind = kind;
        this.group = group;
        this.percent = percent;
        this.fixedAmount = fixedAmount;
    }

    public Long getId() { return id; }
    public LocalDate getMonth() { return month; }
    public BudgetKind getKind() { return kind; }
    public BudgetGroup getGroup() { return group; }
    public BigDecimal getPercent() { return percent; }
    public void setPercent(BigDecimal percent) { this.percent = percent; }
    public BigDecimal getFixedAmount() { return fixedAmount; }
    public void setFixedAmount(BigDecimal fixedAmount) { this.fixedAmount = fixedAmount; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
}
