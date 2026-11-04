package com.moneymgmt.repo;

import com.moneymgmt.domain.RecurringItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringItemRepository extends JpaRepository<RecurringItem, Long> {
}
