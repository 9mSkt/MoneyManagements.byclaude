package com.moneymgmt.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.ImportBatch;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.ImportBatchRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import com.moneymgmt.util.Payday;
import db.migration.V6__imported_income_on_payday;
import db.migration.V7__imported_income_on_own_month_payday;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the payday migrations again, in order, on made-up rows in 2032 inside a test transaction (rolled back).
 * End result: imported income sits on the payday of its own month; typed-in income and spending do not move.
 */
@Transactional
class PaydayMigrationTest extends AbstractWebTest {

    @Autowired DataSource dataSource;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    @Autowired ImportBatchRepository batches;

    private Transaction save(String code, LocalDate date, ImportBatch batch) {
        var c = categories.findByCode(code).orElseThrow();
        var t = new Transaction(date, c.getType(), Money.of("1000"), c, "Sample " + code);
        t.setImportBatch(batch);
        return transactions.saveAndFlush(t);
    }

    private void run(BaseJavaMigration migration) throws Exception {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        migration.migrate(new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return conn;
            }
        });
    }

    private LocalDate dateOf(Transaction t) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT tx_date FROM transaction_record WHERE id = ?", java.sql.Date.class, t.getId()).toLocalDate();
    }

    @Test
    void importedIncomeEndsOnThePaydayOfItsOwnMonth() throws Exception {
        ImportBatch batch = batches.saveAndFlush(new ImportBatch("sample.xlsx", LocalDateTime.now()));
        Transaction nov = save("SALARY", LocalDate.of(2032, 11, 1), batch);       // 28 Nov 2032 is a Sunday → Fri 26
        Transaction oct = save("RENT_INCOME", LocalDate.of(2032, 10, 1), batch);  // 28 Oct 2032 is a Thursday
        Transaction typed = save("SALARY", LocalDate.of(2032, 9, 1), null);       // typed by the owner: stays
        Transaction spending = save("RENT", LocalDate.of(2032, 11, 1), batch);    // spending: stays

        run(new V6__imported_income_on_payday());
        assertThat(dateOf(nov)).as("V6 moved it to the month before").isEqualTo(LocalDate.of(2032, 10, 28));
        run(new V7__imported_income_on_own_month_payday());

        assertThat(dateOf(nov)).isEqualTo(LocalDate.of(2032, 11, 26));
        assertThat(dateOf(oct)).isEqualTo(LocalDate.of(2032, 10, 28));
        assertThat(dateOf(typed)).isEqualTo(LocalDate.of(2032, 9, 1));
        assertThat(dateOf(spending)).isEqualTo(LocalDate.of(2032, 11, 1));
    }

    @Test
    void v7AlsoHandlesIncomeThatV6NeverMoved() throws Exception {
        ImportBatch batch = batches.saveAndFlush(new ImportBatch("sample.xlsx", LocalDateTime.now()));
        Transaction jan = save("SALARY", LocalDate.of(2032, 1, 1), batch);
        run(new V7__imported_income_on_own_month_payday());
        assertThat(dateOf(jan)).isEqualTo(LocalDate.of(2032, 1, 28));   // Wednesday
    }

    @Test
    void flywayRanBothAtStartup() {
        Integer ok = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('6', '7') AND success = TRUE", Integer.class);
        assertThat(ok).isEqualTo(2);
    }

    @Test
    void paydayIsThe28thOrTheFridayBefore() {
        assertThat(Payday.payDate(YearMonth.of(2026, 10), 28)).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(Payday.payDate(YearMonth.of(2026, 11), 28)).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(Payday.payDate(YearMonth.of(2027, 2), 28)).isEqualTo(LocalDate.of(2027, 2, 26));
        assertThat(Payday.payDate(YearMonth.of(2026, 11), 0)).isNull();
        assertThat(V7__imported_income_on_own_month_payday.payDate(YearMonth.of(2026, 11)))
                .isEqualTo(Payday.payDate(YearMonth.of(2026, 11), 28));
    }
}
