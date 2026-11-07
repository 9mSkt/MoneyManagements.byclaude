package com.moneymgmt.service;

import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One year of transactions as an Excel file: a Summary sheet (one row per month, like the yearly report), then
 * one sheet per month that has entries, with that month's totals at the bottom. Labels follow the language.
 * Amounts are computed with BigDecimal; they become spreadsheet numbers only when written to the cells.
 */
@Service
public class YearExportService {

    private final ReportService reports;
    private final TransactionRepository transactions;
    private final MessageSource messages;

    public YearExportService(ReportService reports, TransactionRepository transactions, MessageSource messages) {
        this.reports = reports;
        this.transactions = transactions;
        this.messages = messages;
    }

    public static String fileName(int year) {
        return "money-" + year + "-by-month.xlsx";
    }

    @Transactional(readOnly = true)
    public byte[] yearXlsx(int year, Locale locale) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            summary(wb, st, year, locale);
            List<Transaction> all = transactions.findByDateBetweenOrderByDateAscIdAsc(
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            for (int m = 1; m <= 12; m++) {
                YearMonth ym = YearMonth.of(year, m);
                List<Transaction> rows = all.stream().filter(t -> YearMonth.from(t.getDate()).equals(ym)).toList();
                if (!rows.isEmpty()) {
                    month(wb, st, ym, rows, locale);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void summary(XSSFWorkbook wb, Styles st, int year, Locale locale) {
        Sheet s = wb.createSheet(msg("export.sheet.summary", locale));
        var report = reports.year(year);
        List<String> head = new ArrayList<>(List.of(msg("rep.month", locale), msg("rep.income", locale)));
        for (BudgetGroup g : ReportService.GROUPS) {
            head.add(msg("group." + g, locale));
        }
        head.addAll(List.of(msg("rep.outcome", locale), msg("rep.remaining", locale), msg("rep.savingRate", locale) + " %"));
        Row title = s.createRow(0);
        text(title, 0, msg("rep.yearTitle", locale, year), st.title);
        header(s.createRow(2), head, st);
        int r = 3;
        for (var row : report.rows()) {
            Row x = s.createRow(r++);
            text(x, 0, monthName(row.month(), locale), null);
            summaryCells(x, row, st, row.summary().isEmpty());
        }
        Row total = s.createRow(r);
        text(total, 0, msg("rep.total", locale), st.bold);
        summaryCells(total, report.total(), st, false);
        for (int c = 0; c < total.getLastCellNum(); c++) {
            if (total.getCell(c) != null) {
                total.getCell(c).setCellStyle(c == 0 ? st.bold : st.boldMoney);
            }
        }
        s.createFreezePane(1, 3);
        widths(s, head.size(), 16);
        s.setColumnWidth(0, 14 * 256);
    }

    private static void summaryCells(Row x, ReportService.YearRow row, Styles st, boolean empty) {
        int c = 1;
        money(x, c++, row.summary().income(), st.money);
        for (BudgetGroup g : ReportService.GROUPS) {
            money(x, c++, row.group(g), st.money);
        }
        money(x, c++, row.summary().expense(), st.money);
        money(x, c++, row.summary().remaining(), st.money);
        if (!empty) {
            money(x, c, row.summary().savingRate(), st.money);
        }
    }

    private void month(XSSFWorkbook wb, Styles st, YearMonth ym, List<Transaction> rows, Locale locale) {
        Sheet s = wb.createSheet(sheetName(ym));
        List<String> head = List.of(msg("tx.date", locale), msg("tx.type", locale), msg("tx.group", locale),
                msg("tx.category", locale), msg("tx.description", locale), msg("tx.amount", locale),
                msg("tx.note", locale), msg("tx.tags", locale), msg("export.installment", locale));
        Row title = s.createRow(0);
        text(title, 0, monthName(ym, locale) + " " + ym.getYear(), st.title);
        header(s.createRow(2), head, st);
        int r = 3;
        BigDecimal income = Money.zero();
        BigDecimal expense = Money.zero();
        BigDecimal transfer = Money.zero();
        for (Transaction t : rows) {
            Row x = s.createRow(r++);
            Cell d = x.createCell(0);
            d.setCellValue(t.getDate());
            d.setCellStyle(st.date);
            text(x, 1, msg("tx.type." + t.getType(), locale), null);
            BudgetGroup g = t.getCategory().getBudgetGroup();
            text(x, 2, g == null ? "" : msg("group." + g, locale), null);
            text(x, 3, t.getCategory().name(locale), null);
            text(x, 4, t.getDescription(), null);
            money(x, 5, t.getAmount(), st.money);
            text(x, 6, t.getNote(), null);
            text(x, 7, t.getTags(), null);
            if (t.getInstallment() != null && t.getInstallmentNo() != null) {
                text(x, 8, t.getInstallmentNo() + "/" + t.getInstallment().getMonths(), null);
            }
            switch (t.getType()) {
                case INCOME -> income = income.add(t.getAmount());
                case EXPENSE -> expense = expense.add(t.getAmount());
                case TRANSFER -> transfer = transfer.add(t.getAmount());
            }
        }
        r++;
        r = totalRow(s, r, msg("rep.income", locale), income, st);
        r = totalRow(s, r, msg("rep.outcome", locale), expense, st);
        r = totalRow(s, r, msg("rep.savedInvested", locale), transfer, st);
        totalRow(s, r, msg("rep.remaining", locale), income.subtract(expense).subtract(transfer), st);
        s.createFreezePane(0, 3);
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, 2 + rows.size(), 0, head.size() - 1));
        int[] w = {12, 16, 16, 22, 30, 14, 30, 18, 10};
        for (int i = 0; i < w.length; i++) {
            s.setColumnWidth(i, w[i] * 256);
        }
    }

    private static int totalRow(Sheet s, int r, String label, BigDecimal value, Styles st) {
        Row x = s.createRow(r);
        text(x, 4, label, st.bold);
        money(x, 5, value, st.boldMoney);
        return r + 1;
    }

    /** "Jan 2026" style, always in English so sheet names stay short and safe. */
    static String sheetName(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.getYear();
    }

    private static String monthName(YearMonth ym, Locale locale) {
        return ym.getMonth().getDisplayName(TextStyle.FULL, locale == null ? Locale.ENGLISH : locale);
    }

    private static void header(Row row, List<String> names, Styles st) {
        for (int c = 0; c < names.size(); c++) {
            text(row, c, names.get(c), st.head);
        }
    }

    private static void text(Row row, int c, String value, CellStyle style) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Cell cell = row.createCell(c);
        // Text typed by the user that starts like a formula stays text (it is never a formula in POI anyway).
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void money(Row row, int c, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(Money.scale(value).doubleValue());   // spreadsheet number for display; computed exactly above
        cell.setCellStyle(style);
    }

    private static void widths(Sheet s, int cols, int chars) {
        for (int i = 0; i < cols; i++) {
            s.setColumnWidth(i, chars * 256);
        }
    }

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, key, locale == null ? Locale.ENGLISH : locale);
    }

    private static final class Styles {
        final CellStyle head;
        final CellStyle title;
        final CellStyle bold;
        final CellStyle money;
        final CellStyle boldMoney;
        final CellStyle date;

        Styles(XSSFWorkbook wb) {
            Font b = wb.createFont();
            b.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            short fmt = wb.createDataFormat().getFormat("#,##0.00");
            head = wb.createCellStyle();
            head.setFont(b);
            head.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            title = wb.createCellStyle();
            title.setFont(big);
            bold = wb.createCellStyle();
            bold.setFont(b);
            money = wb.createCellStyle();
            money.setDataFormat(fmt);
            boldMoney = wb.createCellStyle();
            boldMoney.setDataFormat(fmt);
            boldMoney.setFont(b);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        }
    }
}
