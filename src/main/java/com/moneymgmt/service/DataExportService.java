package com.moneymgmt.service;

import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.TransactionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads every table as plain rows: used for the full backup (.xlsx), the daily Google Sheet copy and the
 * transactions CSV. Nothing here changes data.
 */
@Service
public class DataExportService {

    /** All app tables, parents before children (the order a restore would insert them). */
    public static final List<String> TABLES = List.of(
            "import_batch", "category", "account", "account_balance", "installment", "recurring_item",
            "transaction_record", "budget_rule", "goal", "asset", "asset_trade", "dividend", "asset_price",
            "fx_rate", "tax_year", "tax_deduction", "tax_bracket", "app_setting");

    /** One table: column names and rows (values as read from the database). */
    public record Table(String name, List<String> columns, List<List<Object>> rows) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionRepository transactions;

    public DataExportService(JdbcTemplate jdbc, TransactionRepository transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<Table> allTables() {
        List<Table> out = new ArrayList<>();
        for (String t : TABLES) {
            out.add(table(t));
        }
        return out;
    }

    public Table table(String name) {
        if (!TABLES.contains(name)) {
            throw new IllegalArgumentException("Unknown table " + name);
        }
        String order = "app_setting".equals(name) ? "setting_key" : "id";
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        jdbc.query("SELECT * FROM " + name + " ORDER BY " + order, rs -> {
            var meta = rs.getMetaData();
            if (columns.isEmpty()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i).toLowerCase());
                }
            }
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 1; i <= columns.size(); i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        });
        if (columns.isEmpty()) {   // empty table: still list the columns
            jdbc.query("SELECT * FROM " + name + " WHERE 1 = 0", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                var meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i).toLowerCase());
                }
                return null;
            });
        }
        return new Table(name, columns, rows);
    }

    /** Every table on its own sheet. Numbers stay numbers, dates stay dates. */
    public byte[] backupXlsx() throws IOException {
        List<Table> tables = allTables();
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle stamp = wb.createCellStyle();
            stamp.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            for (Table t : tables) {
                Sheet sheet = wb.createSheet(t.name());
                Row hr = sheet.createRow(0);
                for (int c = 0; c < t.columns().size(); c++) {
                    Cell cell = hr.createCell(c);
                    cell.setCellValue(t.columns().get(c));
                    cell.setCellStyle(head);
                }
                int r = 1;
                for (List<Object> row : t.rows()) {
                    Row xr = sheet.createRow(r++);
                    for (int c = 0; c < row.size(); c++) {
                        Object v = row.get(c);
                        if (v == null) {
                            continue;
                        }
                        Cell cell = xr.createCell(c);
                        switch (v) {
                            case BigDecimal b -> cell.setCellValue(b.doubleValue());   // display only; DB keeps exact decimals
                            case Number n -> cell.setCellValue(n.doubleValue());
                            case Boolean b -> cell.setCellValue(b);
                            case java.sql.Date d -> { cell.setCellValue(d.toLocalDate()); cell.setCellStyle(date); }
                            case LocalDate d -> { cell.setCellValue(d); cell.setCellStyle(date); }
                            case Timestamp ts -> { cell.setCellValue(ts.toLocalDateTime()); cell.setCellStyle(stamp); }
                            default -> cell.setCellValue(String.valueOf(v));
                        }
                    }
                }
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        }
    }

    /** Transactions between two dates as CSV (UTF-8 with BOM so Excel shows Thai text). */
    @Transactional(readOnly = true)
    public void transactionsCsv(LocalDate from, LocalDate to, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿');
        w.write("date,type,category_en,category_th,group,amount,fee,description,note,tags,installment_no\r\n");
        for (Transaction t : transactions.findByDateBetweenOrderByDateAscIdAsc(from, to)) {
            var c = t.getCategory();
            List<String> cells = List.of(
                    t.getDate().toString(), t.getType().name(), text(c.getNameEn()), text(c.getNameTh()),
                    c.getBudgetGroup() == null ? "" : c.getBudgetGroup().name(), t.getAmount().toPlainString(),
                    t.getFee() == null ? "" : t.getFee().toPlainString(), text(t.getDescription()), text(t.getNote()),
                    text(t.getTags()), t.getInstallmentNo() == null ? "" : String.valueOf(t.getInstallmentNo()));
            w.write(String.join(",", cells.stream().map(DataExportService::csv).toList()));
            w.write("\r\n");
        }
        w.flush();
    }

    /** Text typed by the user: a leading = + - @ would run as a formula in a spreadsheet, so it is quoted. */
    static String text(String s) {
        if (s == null) {
            return "";
        }
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Counts per table, for the backup / copy screens. */
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class);
            out.put(t, n == null ? 0 : n);
        }
        return out;
    }
}
