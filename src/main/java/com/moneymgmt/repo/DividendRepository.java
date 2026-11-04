package com.moneymgmt.repo;

import com.moneymgmt.domain.Dividend;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DividendRepository extends JpaRepository<Dividend, Long> {
}
