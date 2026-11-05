package db.migration;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * One-time move of imported income to its real payday (owner's request, Nov 2026).
 *
 * <p>The sheet import used to date every entry the 1st of its month tab. The owner is paid on the 28th of the
 * month before (or the last weekday before the 28th when it is a weekend), and that salary is the money for the
 * tab's month. So imported income dated the 1st of month M moves to the payday of month M − 1. Budgets and
 * totals do not change: income from the payday on counts toward the next month. Income the owner typed in
 * is not touched. The payday (28) is fixed here so the migration always does the same thing.
 */
public class V6__imported_income_on_payday extends BaseJavaMigration {

    static final int PAYDAY = 28;

    @Override
    public void migrate(Context context) throws Exception {
        var conn = context.getConnection();
        List<long[]> moves = new ArrayList<>();   // {id, new date as epoch day}
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, tx_date FROM transaction_record WHERE type = 'INCOME' AND import_batch_id IS NOT NULL");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                LocalDate date = rs.getDate(2).toLocalDate();
                if (date.getDayOfMonth() == 1) {
                    moves.add(new long[] {rs.getLong(1), payDate(YearMonth.from(date).minusMonths(1)).toEpochDay()});
                }
            }
        }
        try (PreparedStatement update = conn.prepareStatement("UPDATE transaction_record SET tx_date = ? WHERE id = ?")) {
            for (long[] m : moves) {
                update.setDate(1, Date.valueOf(LocalDate.ofEpochDay(m[1])));
                update.setLong(2, m[0]);
                update.addBatch();
            }
            if (!moves.isEmpty()) {
                update.executeBatch();
            }
        }
    }

    /** The 28th, or the last weekday before it when the 28th is a Saturday or Sunday. */
    public static LocalDate payDate(YearMonth month) {
        LocalDate d = month.atDay(Math.min(PAYDAY, month.lengthOfMonth()));
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
