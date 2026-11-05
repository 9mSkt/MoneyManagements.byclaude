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

    /** Payments of one installment plan, oldest first (the schedule on the plan page). */
    List<Transaction> findByInstallmentOrderByDateAscIdAsc(Installment installment);

    /** Totals grouped by month, category and type (dashboard, budget, reports). */
    @Query("select new com.moneymgmt.repo.CategoryMonthTotal(extract(year from t.date), extract(month from t.date), "
            + "t.category.id, t.type, sum(t.amount)) from Transaction t where t.date between :from and :to "
            + "group by extract(year from t.date), extract(month from t.date), t.category.id, t.type")
    List<CategoryMonthTotal> totalsByMonthAndCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Day-by-day spending without fixed costs, for the calendar heatmap. Rows: [LocalDate, BigDecimal]. */
    @Query("select t.date, sum(t.amount) from Transaction t join t.category c where t.type = "
            + "com.moneymgmt.domain.TxType.EXPENSE and (c.budgetGroup is null or c.budgetGroup <> "
            + "com.moneymgmt.domain.BudgetGroup.FIXED) and t.date between :from and :to group by t.date")
    List<Object[]> dailySpending(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** All entries of a month in the order they happened (for "which entry pushed a group over budget"). */
    @EntityGraph(attributePaths = {"category"})
    List<Transaction> findByDateBetweenOrderByDateAscIdAsc(LocalDate from, LocalDate to);
}
