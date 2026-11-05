package com.moneymgmt.repo;

import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.domain.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    long countByDateBetween(LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = {"category", "installment", "recurringItem"})
    Optional<Transaction> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"category"})
    List<Transaction> findByRecurringItemInAndDateBetween(List<RecurringItem> items, LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = {"category"})
    List<Transaction> findByInstallmentInAndDateBetween(List<Installment> items, LocalDate from, LocalDate to);

    /** Same category and same description in that month (e.g. a fixed cost that came in with the sheet import). */
    @EntityGraph(attributePaths = {"category"})
    @Query("select t from Transaction t where t.category = :category and t.date between :from and :to "
            + "and lower(t.description) = lower(:description)")
    List<Transaction> findSame(@Param("category") Category category, @Param("description") String description,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Entries of one category code in a date range, oldest first (incentive and bonus of a month). */
    @EntityGraph(attributePaths = {"category"})
    List<Transaction> findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc(String code, LocalDate from, LocalDate to);

    long countByInstallment(Installment installment);
}
