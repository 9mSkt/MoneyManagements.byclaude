package com.moneymgmt.repo;

import com.moneymgmt.domain.FxRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    Optional<FxRate> findByCurrencyAndDate(String currency, LocalDate date);

    Optional<FxRate> findFirstByCurrencyOrderByDateDesc(String currency);

    List<FxRate> findByCurrencyOrderByDateAsc(String currency);
}
