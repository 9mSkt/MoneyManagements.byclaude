package com.moneymgmt.migration;

import db.migration.V6__imported_income_on_payday;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.ImportBatch;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.ImportBatchRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;

/** Runs the payday migration again on made-up rows in 2032, inside a test transaction (rolled back). */
@Transactional
class V6MigrationTest extends AbstractWebTest {

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

    @Test
    void movesImportedIncomeToThePaydayOfTheMonthBefore() throws Exception {
        ImportBatch batch = batches.saveAndFlush(new ImportBatch("sample.xlsx", LocalDateTime.now()));
        Transaction salary = save("SALARY", LocalDate.of(2032, 11, 1), batch);        // 28 Oct 2032 is a Thursday
        Transaction rent = save("RENT_INCOME", LocalDate.of(2032, 12, 1), batch);     // 28 Nov 2032 is a Sunday → Fri 26
        Transaction typed = save("SALARY", LocalDate.of(2032, 9, 1), null);           // typed by the owner: stays
        Transaction spending = save("RENT", LocalDate.of(2032, 11, 1), batch);        // spending: stays

        Connection conn = DataSourceUtils.getConnection(dataSource);
        new V6__imported_income_on_payday().migrate(new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return conn;
            }
        });

        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        java.util.function.Function<Long, LocalDate> dateOf = id -> jdbc.queryForObject(
                "SELECT tx_date FROM transaction_record WHERE id = ?", java.sql.Date.class, id).toLocalDate();
        assertThat(dateOf.apply(salary.getId())).isEqualTo(LocalDate.of(2032, 10, 28));
        assertThat(dateOf.apply(rent.getId())).isEqualTo(LocalDate.of(2032, 11, 26));
        assertThat(dateOf.apply(typed.getId())).isEqualTo(LocalDate.of(2032, 9, 1));
        assertThat(dateOf.apply(spending.getId())).isEqualTo(LocalDate.of(2032, 11, 1));
    }

    @Test
    void flywayRanTheJavaMigrationAtStartup() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Integer ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = TRUE", Integer.class);
        assertThat(ok).isEqualTo(1);
    }

    @Test
    void paydayFallsBackToFridayAtWeekends() {
        assertThat(V6__imported_income_on_payday.payDate(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(V6__imported_income_on_payday.payDate(YearMonth.of(2026, 11))).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(V6__imported_income_on_payday.payDate(YearMonth.of(2025, 12))).isEqualTo(LocalDate.of(2025, 12, 26));
    }
}
