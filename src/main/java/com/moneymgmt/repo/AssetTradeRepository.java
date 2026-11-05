package com.moneymgmt.repo;

import com.moneymgmt.domain.Asset;
import com.moneymgmt.domain.AssetTrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetTradeRepository extends JpaRepository<AssetTrade, Long> {

    List<AssetTrade> findByAssetOrderByTradeDateAscIdAsc(Asset asset);

    List<AssetTrade> findAllByOrderByTradeDateAscIdAsc();
}
