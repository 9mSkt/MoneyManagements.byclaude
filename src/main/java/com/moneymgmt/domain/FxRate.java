package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** THB per one unit of a currency (e.g. USD/THB 32.98) on a day, typed in by the owner. */
@Entity
@Table(name = "fx_rate")
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal rate;

    protected FxRate() {
    }

    public FxRate(String currency, LocalDate date, BigDecimal rate) {
        this.currency = currency;
        this.date = date;
        this.rate = rate;
    }

    public Long getId() { return id; }
    public String getCurrency() { return currency; }
    public LocalDate getDate() { return date; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
}
