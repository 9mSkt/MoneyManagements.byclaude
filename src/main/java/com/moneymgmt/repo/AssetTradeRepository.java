package com.moneymgmt.repo;

import com.moneymgmt.domain.AssetTrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetTradeRepository extends JpaRepository<AssetTrade, Long> {
}
