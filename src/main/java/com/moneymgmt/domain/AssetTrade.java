package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "asset_trade")
public class AssetTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeSide side;

    /** Amount in the trade currency. */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** THB per one unit of the trade currency; null for THB trades. */
    @Column(name = "fx_rate")
    private BigDecimal fxRate;

    @Column(name = "price_per_unit", nullable = false)
    private BigDecimal pricePerUnit;

    @Column(nullable = false)
    private BigDecimal units;

    @Column(nullable = false)
    private BigDecimal fee = BigDecimal.ZERO;

    protected AssetTrade() {
    }

    public AssetTrade(Asset asset, LocalDate tradeDate, TradeSide side, BigDecimal amount, String currency,
                      BigDecimal fxRate, BigDecimal pricePerUnit, BigDecimal units, BigDecimal fee) {
        this.asset = asset;
        this.tradeDate = tradeDate;
        this.side = side;
        this.amount = amount;
        this.currency = currency;
        this.fxRate = fxRate;
        this.pricePerUnit = pricePerUnit;
        this.units = units;
        this.fee = fee;
    }

    public Long getId() { return id; }
    public Asset getAsset() { return asset; }
    public LocalDate getTradeDate() { return tradeDate; }
    public TradeSide getSide() { return side; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getFxRate() { return fxRate; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public BigDecimal getUnits() { return units; }
    public BigDecimal getFee() { return fee; }
}
