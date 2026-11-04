package com.moneymgmt.importer;

import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.domain.TxType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything read from the sheet, before anything is saved. Shown on the preview page, kept in the session
 * until the owner confirms, then written by {@link ImportService}.
 */
public record ImportPlan(
        List<MonthSummary> months,
        List<TxLine> transactions,
        List<InstallmentLine> installments,
        List<RecurringLine> recurring,
        List<BudgetLine> budgetRules,
        List<AssetLine> assets,
        List<TaxLine> taxYears,
        List<Note> notes) implements Serializable {

    /** A category by code; created on import if it does not exist yet. */
    public record CatRef(String code, String nameEn, String nameTh, TxType type, BudgetGroup group,
                         boolean needsReview) implements Serializable {
    }

    public record TxLine(LocalDate date, TxType type, CatRef category, BigDecimal amount, String description,
                         String note, String installmentKey, Integer installmentNo,
                         BigDecimal incentivePercent) implements Serializable {
    }

    public record InstallmentLine(String key, String item, BigDecimal fullPrice, BigDecimal monthlyAmount, int months,
                                  BigDecimal interestPercent, LocalDate startMonth, int paidCount,
                                  CatRef category) implements Serializable {
    }

    public record RecurringLine(String name, BigDecimal amount, CatRef category) implements Serializable {
    }

    public record BudgetLine(LocalDate month, BudgetGroup group, BigDecimal percent) implements Serializable {
    }

    public record TradeLine(LocalDate date, TradeSide side, BigDecimal amount, String currency, BigDecimal fxRate,
                            BigDecimal pricePerUnit, BigDecimal units, BigDecimal fee) implements Serializable {
    }

    public record DividendLine(LocalDate date, BigDecimal amount, BigDecimal withholdingTax, String currency)
            implements Serializable {
    }

    public record AssetLine(String name, AssetType type, String currency, BigDecimal currentPrice, LocalDate priceDate,
                            List<TradeLine> trades, List<DividendLine> dividends) implements Serializable {
    }

    public record DeductionLine(String code, String label, BigDecimal amount) implements Serializable {
    }

    public record TaxLine(int year, BigDecimal monthlySalary, BigDecimal bonus, List<DeductionLine> deductions)
            implements Serializable {
    }

    /** Totals per month, so the owner can compare them with the sheet before confirming. */
    public record MonthSummary(LocalDate month, BigDecimal income, BigDecimal fixedCost, BigDecimal spending,
                               int rows) implements Serializable {
        public BigDecimal remaining() {
            return income.subtract(fixedCost).subtract(spending);
        }
    }

    /** A message for the owner: i18n key plus plain-text arguments. */
    public record Note(String key, List<String> args) implements Serializable {
        public Object[] argArray() {
            return args.toArray();
        }
    }

    public long incomeCount() {
        return transactions.stream().filter(t -> t.type() == TxType.INCOME).count();
    }

    public long expenseCount() {
        return transactions.stream().filter(t -> t.type() == TxType.EXPENSE).count();
    }

    public int tradeCount() {
        return assets.stream().mapToInt(a -> a.trades().size()).sum();
    }

    public int dividendCount() {
        return assets.stream().mapToInt(a -> a.dividends().size()).sum();
    }
}
