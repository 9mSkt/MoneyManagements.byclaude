package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One income, expense or transfer. Stored in table transaction_record ("transaction" is an SQL keyword). */
@Entity
@Table(name = "transaction_record")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    private String description;
    private String note;
    private String tags;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_id")
    private Installment installment;

    @Column(name = "installment_no")
    private Integer installmentNo;

    @Column(name = "kpi_percent")
    private BigDecimal kpiPercent;

    @Column(name = "incentive_percent")
    private BigDecimal incentivePercent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Transaction() {
    }

    public Transaction(LocalDate date, TxType type, BigDecimal amount, Category category, String description) {
        this.date = date;
        this.type = type;
        this.amount = amount;
        this.category = category;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public TxType getType() { return type; }
    public void setType(TxType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Installment getInstallment() { return installment; }
    public void setInstallment(Installment installment) { this.installment = installment; }
    public Integer getInstallmentNo() { return installmentNo; }
    public void setInstallmentNo(Integer installmentNo) { this.installmentNo = installmentNo; }
    public BigDecimal getKpiPercent() { return kpiPercent; }
    public void setKpiPercent(BigDecimal kpiPercent) { this.kpiPercent = kpiPercent; }
    public BigDecimal getIncentivePercent() { return incentivePercent; }
    public void setIncentivePercent(BigDecimal incentivePercent) { this.incentivePercent = incentivePercent; }
    public ImportBatch getImportBatch() { return importBatch; }
    public void setImportBatch(ImportBatch importBatch) { this.importBatch = importBatch; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
