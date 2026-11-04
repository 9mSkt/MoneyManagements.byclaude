package com.moneymgmt.repo;

import com.moneymgmt.domain.Installment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {
}
