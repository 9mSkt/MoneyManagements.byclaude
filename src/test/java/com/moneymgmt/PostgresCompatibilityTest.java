package com.moneymgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moneymgmt.config.DataCopyTool;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import com.moneymgmt.web.NavItem;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The app on a real PostgreSQL (what Neon runs), started in-process without Docker: all Flyway migrations, every
 * page and chart query, and the one-time local → online data copy. Made-up data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "tester")
class PostgresCompatibilityTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("app.username", () -> "tester");
        String hash = new BCryptPasswordEncoder(4).encode("correct-horse-battery");
        r.add("app.password-hash", () -> hash);
    }

    @AfterAll
    static void stop() throws IOException {
        PG.close();
    }

    @Autowired MockMvc mvc;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    @Test
    void everyPageAndChartWorksOnPostgres() throws Exception {
        var shop = categories.findByCode("SHOPPING").orElseThrow();
        var salary = categories.findByCode("SALARY").orElseThrow();
        var today = AppTime.today();
        transactions.save(new Transaction(today, salary.getType(), Money.of(40000), salary, "Sample salary"));
        transactions.save(new Transaction(today, shop.getType(), Money.of(900), shop, "Sample shoes"));

        for (NavItem item : NavItem.ALL) {
            mvc.perform(get(item.path())).andExpect(status().isOk());
        }
        String m = AppTime.thisMonth().toString();
        for (String path : new String[] {"/api/dashboard?month=" + m, "/api/investments/charts", "/api/accounts/history",
                "/api/reports/year?year=" + today.getYear(), "/transactions/installments?month=" + m,
                "/reports/export/backup.xlsx", "/reports/export/transactions.csv", "/settings/categories",
                "/budget?month=" + m, "/tax"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void copyToolMovesLocalDataToPostgres() throws Exception {
        // A made-up "local" H2 database with one entry, copied into a fresh PostgreSQL database.
        String h2 = "jdbc:h2:mem:copysource;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (Connection admin = PG.getPostgresDatabase().getConnection(); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE copytarget");
        }
        String pg = PG.getJdbcUrl("postgres", "copytarget");
        DataCopyTool tool = new DataCopyTool(new PrintStream(OutputStream.nullOutputStream()));
        var source = new DataCopyTool.Db(h2, "sa", "");
        var target = new DataCopyTool.Db(pg, "postgres", "postgres");

        tool.copy(source, target, false);   // creates the schema on both sides first
        try (Connection c = DriverManager.getConnection(h2, "sa", ""); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO transaction_record (id, tx_date, type, amount, category_id, description, created_at) "
                    + "SELECT 41, DATE '2031-02-03', 'EXPENSE', 123.45, id, 'Sample copy', CURRENT_TIMESTAMP "
                    + "FROM category WHERE code = 'SHOPPING'");
            st.executeUpdate("INSERT INTO goal (name, target_amount, saved_amount) VALUES ('Sample goal', 1000, 250)");
        }

        assertThatThrownBy(() -> {
            // the target now has the schema but no transactions, so the first real copy works ...
            tool.copy(source, target, false);
            // ... and a second one without --replace is refused.
            tool.copy(source, target, false);
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("--replace");

        try (Connection c = DriverManager.getConnection(pg, "postgres", "postgres"); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT amount, description FROM transaction_record WHERE id = 41");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("123.45");
            assertThat(rs.getString(2)).isEqualTo("Sample copy");
            // New rows get ids after the copied ones.
            st.executeUpdate("INSERT INTO transaction_record (tx_date, type, amount, category_id, created_at) "
                    + "SELECT DATE '2031-02-04', 'EXPENSE', 1, id, CURRENT_TIMESTAMP FROM category WHERE code = 'SHOPPING'");
            rs = st.executeQuery("SELECT MAX(id) FROM transaction_record");
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(42);
            rs = st.executeQuery("SELECT COUNT(*) FROM category");
            rs.next();
            assertThat(rs.getLong(1)).isGreaterThan(20);
        }
        tool.copy(source, target, true);   // --replace overwrites
    }
}
