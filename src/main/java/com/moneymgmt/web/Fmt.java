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

    /** Units, NAV or price: grouped, up to {@code maxDecimals} decimals, no trailing zeros ("1,234.5678"). */
    public String number(BigDecimal value, int maxDecimals) {
        if (value == null) {
            return "—";
        }
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0." + "#".repeat(Math.max(maxDecimals, 1)),
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        df.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return df.format(value);
    }

    /** Money in any currency: "฿1,234.50", "$455.08", "€..."; other codes as "CHF 1.00". */
    public String money(BigDecimal value, String currency) {
        if (value == null) {
            return "—";
        }
        String sign = value.signum() < 0 ? "−" : "";
        String body = Money.format(value.abs());
        return switch (currency == null ? "THB" : currency) {
            case "THB" -> sign + "฿" + body;
            case "USD" -> sign + "$" + body;
            default -> sign + currency + " " + body;
        };
    }

    /** "+12.30%" / "−4.00%" for profit, with the sign. */
    public String signedPercent(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        return (value.signum() > 0 ? "+" : value.signum() < 0 ? "−" : "") + Money.format(value.abs()) + "%";
    }

    /** "+฿1,000.00" / "−฿50.00" for profit. */
    public String signedBaht(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        return (value.signum() > 0 ? "+" : "") + baht(value);
    }

    /** CSS class for a profit number: income (gain), expense (loss) or none. */
    public String gain(BigDecimal value) {
        return value == null || value.signum() == 0 ? "" : value.signum() > 0 ? "income" : "expense";
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
