package com.moneymgmt.repo;

import com.moneymgmt.domain.RecurringItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecurringItemRepository extends JpaRepository<RecurringItem, Long> {

    @EntityGraph(attributePaths = {"category"})
    @Query("select r from RecurringItem r order by r.active desc, r.dayOfMonth, r.category.sortOrder, r.name")
    List<RecurringItem> findAllForList();

    @EntityGraph(attributePaths = {"category"})
    List<RecurringItem> findByActiveTrue();

    @EntityGraph(attributePaths = {"category"})
    Optional<RecurringItem> findWithCategoryById(Long id);
}
