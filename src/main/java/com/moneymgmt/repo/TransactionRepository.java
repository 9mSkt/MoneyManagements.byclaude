package com.moneymgmt.repo;

import com.moneymgmt.domain.Transaction;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    long countByDateBetween(LocalDate from, LocalDate to);
}
