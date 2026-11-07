package com.moneymgmt.config;

import com.moneymgmt.service.DataExportService;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.flywaydb.core.Flyway;

/**
 * One-time move of all data from the local H2 database to the online PostgreSQL (Neon):
 * {@code copy-data} brings both databases to the latest schema, then copies every table with the same ids.
 * It refuses to overwrite a target that already has transactions, unless {@code --replace} is given.
 */
public final class DataCopyTool {

    public record Db(String url, String user, String password) {
        Connection open() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
    }

    private final PrintStream out;

    public DataCopyTool(PrintStream out) {
        this.out = out;
    }

    /** Returns the number of rows copied. */
    public int copy(Db source, Db target, boolean replace) throws SQLException {
        migrate(source);
        migrate(target);
        int total = 0;
        try (Connection from = source.open(); Connection to = target.open()) {
            to.setAutoCommit(false);
            long existing = count(to, "transaction_record");
            if (existing > 0 && !replace) {
                throw new IllegalStateException("The online database already has " + existing
                        + " transactions. Nothing was copied. Run again with --replace to overwrite it.");
            }
            try {
                List<String> reversed = new ArrayList<>(DataExportService.TABLES);
                Collections.reverse(reversed);
                try (Statement st = to.createStatement()) {
                    for (String t : reversed) {
                        st.executeUpdate("DELETE FROM " + t);
                    }
                }
                for (String t : DataExportService.TABLES) {
                    int n = copyTable(from, to, t);
                    total += n;
                    out.printf("  %-20s %6d rows%n", t, n);
                }
                to.commit();
            } catch (SQLException | RuntimeException e) {
                to.rollback();
                throw e;
            }
        }
        return total;
    }

    private static void migrate(Db db) {
        Flyway.configure().dataSource(db.url(), db.user(), db.password())
                .locations("classpath:db/migration").load().migrate();
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int copyTable(Connection from, Connection to, String table) throws SQLException {
        boolean hasId = !"app_setting".equals(table);
        int n = 0;
        long maxId = 0;
        try (Statement st = from.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + (hasId ? " ORDER BY id" : ""))) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                names.add(meta.getColumnLabel(i).toLowerCase());
            }
            String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                    + String.join(", ", Collections.nCopies(cols, "?")) + ")";
            try (PreparedStatement ins = to.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        ins.setObject(i, rs.getObject(i));
                    }
                    ins.addBatch();
                    if (hasId) {
                        maxId = Math.max(maxId, rs.getLong("id"));
                    }
                    if (++n % 500 == 0) {
                        ins.executeBatch();
                    }
                }
                ins.executeBatch();
            }
        }
        if (hasId) {
            try (Statement st = to.createStatement()) {
                // New rows made online get ids after the copied ones (works on PostgreSQL and H2).
                st.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + (maxId + 1));
            }
        }
        return n;
    }
}
