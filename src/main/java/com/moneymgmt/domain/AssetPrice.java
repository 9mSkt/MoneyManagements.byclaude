package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A price (NAV for funds) the owner typed in for one day. */
@Entity
@Table(name = "asset_price")
public class AssetPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(name = "price_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal price;

    protected AssetPrice() {
    }

    public AssetPrice(Asset asset, LocalDate date, BigDecimal price) {
        this.asset = asset;
        this.date = date;
        this.price = price;
    }

    public Long getId() { return id; }
    public Asset getAsset() { return asset; }
    public LocalDate getDate() { return date; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
