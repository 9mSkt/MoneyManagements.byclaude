package com.moneymgmt.repo;

import com.moneymgmt.domain.Goal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    /** Goals with a deadline first (soonest first), then the rest by name. */
    @Query("select g from Goal g order by case when g.deadline is null then 1 else 0 end, g.deadline, g.name")
    List<Goal> findAllForList();
}
