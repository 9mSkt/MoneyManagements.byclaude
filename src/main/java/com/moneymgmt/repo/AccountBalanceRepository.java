package com.moneymgmt.repo;

import com.moneymgmt.domain.Account;
import com.moneymgmt.domain.AccountBalance;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    Optional<AccountBalance> findByAccountAndDate(Account account, LocalDate date);

    List<AccountBalance> findByAccountOrderByDateDescIdDesc(Account account);

    @Query("select b from AccountBalance b join fetch b.account order by b.date asc, b.id asc")
    List<AccountBalance> findAllWithAccount();
}
