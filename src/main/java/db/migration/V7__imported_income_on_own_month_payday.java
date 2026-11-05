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
 * Correction of V6 (owner, Nov 2026): the salary paid on the 28th is the money for that same month (it pays that
 * month's bills), so imported income belongs on the payday of its own month tab, not the month before.
 *
 * <p>For imported income only (income the owner typed in is not touched):
 * <ul>
 *   <li>dated the 1st of month M (never moved by V6) → payday of M;</li>
 *   <li>dated on the payday of month M (moved there by V6 from the 1st of M + 1) → payday of M + 1.</li>
 * </ul>
 * Payday is the 28th, or the last weekday before it when the 28th is a Saturday or Sunday (fixed here so the
 * migration always does the same thing).
 */
public class V7__imported_income_on_own_month_payday extends BaseJavaMigration {

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
                YearMonth m = YearMonth.from(date);
                LocalDate target = null;
                if (date.getDayOfMonth() == 1) {
                    target = payDate(m);
                } else if (date.equals(payDate(m))) {
                    target = payDate(m.plusMonths(1));
                }
                if (target != null) {
                    moves.add(new long[] {rs.getLong(1), target.toEpochDay()});
                }
            }
        }
        try (PreparedStatement update = conn.prepareStatement("UPDATE transaction_record SET tx_date = ? WHERE id = ?")) {
            for (long[] mv : moves) {
                update.setDate(1, Date.valueOf(LocalDate.ofEpochDay(mv[1])));
                update.setLong(2, mv[0]);
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
