package com.moneymgmt.repo;

import com.moneymgmt.domain.ImportBatch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findAllByOrderByImportedAtDesc();
}
