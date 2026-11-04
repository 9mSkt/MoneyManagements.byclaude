package com.moneymgmt.repo;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.BudgetKind;
import com.moneymgmt.domain.BudgetRule;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRuleRepository extends JpaRepository<BudgetRule, Long> {

    Optional<BudgetRule> findByMonthAndKindAndGroup(LocalDate month, BudgetKind kind, BudgetGroup group);
}
