package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An account balance typed in by the owner on a date. For a credit account it is the amount owed. */
@Entity
@Table(name = "account_balance")
public class AccountBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "balance_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal balance;

    protected AccountBalance() {
    }

    public AccountBalance(Account account, LocalDate date, BigDecimal balance) {
        this.account = account;
        this.date = date;
        this.balance = balance;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public LocalDate getDate() { return date; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}
