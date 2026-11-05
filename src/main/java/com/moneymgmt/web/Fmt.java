package com.moneymgmt.web;

import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Number formatting for templates: {@code ${@fmt.baht(x)}}. */
@Component("fmt")
public class Fmt {

    /** ฿12,345.00 (−฿500.00 for negatives). */
    public String baht(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.signum() < 0 ? "−฿" + Money.format(value.negate()) : "฿" + Money.format(value);
    }

    /** Amount with the sign it has on the owner's balance: +฿ for income, −฿ for expense, none for transfers. */
    public String signed(Transaction t) {
        String plain = baht(t.getAmount());
        if (t.getType() == TxType.INCOME) {
            return "+" + plain;
        }
        if (t.getType() == TxType.EXPENSE) {
            return t.getAmount().signum() < 0 ? "+฿" + Money.format(t.getAmount().negate()) : "−" + plain;
        }
        return plain;
    }

    /** 95.5 → "95.5%", 10.00 → "10%". */
    public String percent(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString() + "%";
    }

    /** CSS class for a transaction's colour: income / expense / investment (transfers). */
    public String kind(TxType type) {
        return switch (type) {
            case INCOME -> "income";
            case EXPENSE -> "expense";
            case TRANSFER -> "investment";
        };
    }

    /** Colour key for a budget group dot ("g-EAT"), or the type for income. */
    public String dot(Transaction t) {
        return "g-" + (t.getCategory().getBudgetGroup() != null ? t.getCategory().getBudgetGroup().name()
                : t.getType().name());
    }
}
