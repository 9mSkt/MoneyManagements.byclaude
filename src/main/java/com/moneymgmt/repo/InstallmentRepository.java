package com.moneymgmt.repo;

import com.moneymgmt.domain.Installment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

    /** Running plans first, then finished ones; newest start first. */
    @EntityGraph(attributePaths = {"category"})
    @Query("select i from Installment i order by case when i.paidCount >= i.months then 1 else 0 end, "
            + "i.startMonth desc, i.item")
    List<Installment> findAllForList();

    @EntityGraph(attributePaths = {"category"})
    Optional<Installment> findWithCategoryById(Long id);
}
