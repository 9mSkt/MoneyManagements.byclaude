package com.moneymgmt.repo;

import com.moneymgmt.domain.TaxBracket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxBracketRepository extends JpaRepository<TaxBracket, Long> {

    List<TaxBracket> findAllByOrderByIncomeFromAsc();
}
