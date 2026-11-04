package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "tax_deduction")
public class TaxDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_year_id", nullable = false)
    private TaxYear taxYear;

    /** EXPENSE, PERSONAL, SOCIAL_SECURITY, PVD, LIFE_INSURANCE, RMF, SSF, THAI_ESG, OTHER. */
    @Column(nullable = false)
    private String code;

    private String label;

    @Column(nullable = false)
    private BigDecimal amount;

    protected TaxDeduction() {
    }

    public TaxDeduction(String code, String label, BigDecimal amount) {
        this.code = code;
        this.label = label;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public TaxYear getTaxYear() { return taxYear; }
    void setTaxYear(TaxYear taxYear) { this.taxYear = taxYear; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
