package com.moneymgmt.repo;

import com.moneymgmt.domain.TaxBracket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxBracketRepository extends JpaRepository<TaxBracket, Long> {
}
