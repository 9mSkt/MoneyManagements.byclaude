package com.moneymgmt.repo;

import com.moneymgmt.domain.TaxYear;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxYearRepository extends JpaRepository<TaxYear, Long> {

    Optional<TaxYear> findByYear(int year);
}
