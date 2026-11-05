package com.moneymgmt.repo;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetPrice;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetPriceRepository extends JpaRepository<AssetPrice, Long> {

    Optional<AssetPrice> findByAssetAndDate(Asset asset, LocalDate date);

    List<AssetPrice> findByAssetOrderByDateAsc(Asset asset);

    List<AssetPrice> findAllByOrderByDateAsc();
}
