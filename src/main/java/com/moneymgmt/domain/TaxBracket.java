package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "tax_bracket")
public class TaxBracket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "income_from", nullable = false)
    private BigDecimal incomeFrom;

    /** Null means no upper limit. */
    @Column(name = "income_to")
    private BigDecimal incomeTo;

    /** Percent, e.g. 5.00. */
    @Column(nullable = false)
    private BigDecimal rate;

    protected TaxBracket() {
    }

    public TaxBracket(BigDecimal incomeFrom, BigDecimal incomeTo, BigDecimal rate) {
        this.incomeFrom = incomeFrom;
        this.incomeTo = incomeTo;
        this.rate = rate;
    }

    public Long getId() { return id; }
    public BigDecimal getIncomeFrom() { return incomeFrom; }
    public void setIncomeFrom(BigDecimal incomeFrom) { this.incomeFrom = incomeFrom; }
    public BigDecimal getIncomeTo() { return incomeTo; }
    public void setIncomeTo(BigDecimal incomeTo) { this.incomeTo = incomeTo; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
}
