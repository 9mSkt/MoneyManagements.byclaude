package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "dividend")
public class Dividend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(name = "pay_date", nullable = false)
    private LocalDate payDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "withholding_tax", nullable = false)
    private BigDecimal withholdingTax = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    protected Dividend() {
    }

    public Dividend(Asset asset, LocalDate payDate, BigDecimal amount, BigDecimal withholdingTax,
                    String currency, BigDecimal fxRate) {
        this.asset = asset;
        this.payDate = payDate;
        this.amount = amount;
        this.withholdingTax = withholdingTax;
        this.currency = currency;
        this.fxRate = fxRate;
    }

    public Long getId() { return id; }
    public Asset getAsset() { return asset; }
    public LocalDate getPayDate() { return payDate; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getWithholdingTax() { return withholdingTax; }
    public String getCurrency() { return currency; }
    public BigDecimal getFxRate() { return fxRate; }

    /** Changes every value of the dividend (edit form). */
    public void update(LocalDate payDate, BigDecimal amount, BigDecimal withholdingTax, BigDecimal fxRate) {
        this.payDate = payDate;
        this.amount = amount;
        this.withholdingTax = withholdingTax;
        this.fxRate = fxRate;
    }
}
