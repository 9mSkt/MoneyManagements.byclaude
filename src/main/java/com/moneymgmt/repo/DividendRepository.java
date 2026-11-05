package com.moneymgmt.repo;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.Dividend;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DividendRepository extends JpaRepository<Dividend, Long> {

    List<Dividend> findByAssetOrderByPayDateAscIdAsc(Asset asset);

    List<Dividend> findAllByOrderByPayDateAscIdAsc();
}
