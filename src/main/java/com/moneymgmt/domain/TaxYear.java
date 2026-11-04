package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tax_year")
public class TaxYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tax_year", nullable = false, unique = true)
    private int year;

    @Column(name = "monthly_salary", nullable = false)
    private BigDecimal monthlySalary = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal bonus = BigDecimal.ZERO;

    @Column(name = "other_income", nullable = false)
    private BigDecimal otherIncome = BigDecimal.ZERO;

    @OneToMany(mappedBy = "taxYear", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaxDeduction> deductions = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    protected TaxYear() {
    }

    public TaxYear(int year) {
        this.year = year;
    }

    public void addDeduction(TaxDeduction deduction) {
        deduction.setTaxYear(this);
        deductions.add(deduction);
    }

    public Long getId() { return id; }
    public int getYear() { return year; }
    public BigDecimal getMonthlySalary() { return monthlySalary; }
    public void setMonthlySalary(BigDecimal monthlySalary) { this.monthlySalary = monthlySalary; }
    public BigDecimal getBonus() { return bonus; }
    public void setBonus(BigDecimal bonus) { this.bonus = bonus; }
    public BigDecimal getOtherIncome() { return otherIncome; }
    public void setOtherIncome(BigDecimal otherIncome) { this.otherIncome = otherIncome; }
    public List<TaxDeduction> getDeductions() { return deductions; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
}
