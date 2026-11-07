package com.moneymgmt.repo;

import com.moneymgmt.domain.Account;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByOrderByActiveDescTypeAscNameAsc();

    /** Before deleting an account: transactions keep their entries, without the account. */
    @Modifying
    @Query(value = "UPDATE transaction_record SET account_id = NULL WHERE account_id = :id", nativeQuery = true)
    void clearTransactionAccount(Long id);

    @Modifying
    @Query(value = "UPDATE recurring_item SET account_id = NULL WHERE account_id = :id", nativeQuery = true)
    void clearRecurringAccount(Long id);
}
