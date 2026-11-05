package com.moneymgmt.repo;

import com.moneymgmt.domain.Asset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findAllByOrderByTypeAscNameAsc();
}
