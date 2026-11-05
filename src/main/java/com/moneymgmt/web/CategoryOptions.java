package com.moneymgmt.web;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Active categories for drop-downs, grouped by type and then by budget group (for &lt;optgroup&gt;). */
public record CategoryOptions(List<Category> all) {

    public record Group(BudgetGroup group, List<Category> categories) {
    }

    public List<Group> forType(TxType type) {
        Map<BudgetGroup, List<Category>> byGroup = new LinkedHashMap<>();
        List<Category> noGroup = new ArrayList<>();
        for (Category c : all) {
            if (c.getType() != type) {
                continue;
            }
            if (c.getBudgetGroup() == null) {
                noGroup.add(c);
            } else {
                byGroup.computeIfAbsent(c.getBudgetGroup(), g -> new ArrayList<>()).add(c);
            }
        }
        List<Group> groups = new ArrayList<>();
        if (!noGroup.isEmpty()) {
            groups.add(new Group(null, noGroup));
        }
        byGroup.forEach((g, list) -> groups.add(new Group(g, list)));
        return groups;
    }

    public List<Group> getExpense() {
        return forType(TxType.EXPENSE);
    }

    public List<Group> getIncome() {
        return forType(TxType.INCOME);
    }

    public List<Group> getTransfer() {
        return forType(TxType.TRANSFER);
    }
}
