package com.moneymgmt.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds a small workbook in the same shape as the owner's sheet, filled with MADE-UP numbers only. */
final class SampleWorkbook {

    private SampleWorkbook() {
    }

    static byte[] build() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

            wb.createSheet("Summary Report").createRow(0).createCell(0).setCellValue("Income/Spending List");
            month(wb.createSheet("Jan26"), false, "1/3");
            month(wb.createSheet("Feb26"), true, "2/3");
            wb.createSheet("Renovate");
            investment(wb.createSheet("Investment"), dateStyle);
            tax(wb.createSheet("Tax"));
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** One month tab. {@code withBonus} adds an extra income row, shifting everything below it down by one. */
    private static void month(Sheet s, boolean withBonus, String laptopPeriod) {
        set(s, 0, 2, "Income (Bath)");
        set(s, 0, 4, "Item"); set(s, 0, 6, "Period"); set(s, 0, 7, "Full Price"); set(s, 0, 8, "Bath");
        set(s, 0, 21, "Incentive(%)"); set(s, 0, 22, "Incentive(Bath)");
        set(s, 1, 4, "Gas"); set(s, 1, 9, "Shopping"); set(s, 1, 14, "Eat/Lifestyle");
        set(s, 1, 21, 10.0); set(s, 1, 22, 2000.0);

        int r = 1;
        set(s, r, 0, "Salary"); set(s, r++, 2, 20000.0);
        set(s, r, 0, "Rent"); set(s, r++, 2, 5000.0);
        set(s, r, 0, "Phone"); set(s, r++, 2, 200.0);
        set(s, r, 0, "Incentive");
        s.getRow(r).createCell(2).setCellFormula("IF(TRUE,W2,0)");
        r++;
        if (withBonus) {
            set(s, r, 0, "Bonus"); set(s, r++, 2, 10000.0);
        }
        set(s, r++, 0, "Total");
        set(s, r++, 2, "Fix cost (Bath)");
        set(s, r, 0, "Mom"); set(s, r++, 2, 1500.0);
        set(s, r, 0, "Car"); set(s, r++, 2, 6000.0);
        set(s, r, 0, "Gym membership"); set(s, r++, 2, 900.0);
        set(s, r, 0, "Electric Bill"); set(s, r++, 2, 1200.5);
        set(s, r++, 0, "Total");
        set(s, r++, 0, "Remain");
        r++;
        set(s, r, 0, "Plan"); set(s, r++, 1, "%");
        set(s, r, 0, "Eat"); set(s, r++, 1, 0.4);
        set(s, r, 0, "Shopping"); set(s, r++, 1, 0.4);
        set(s, r, 0, "Gas"); set(s, r++, 1, 0.2);
        set(s, r, 0, "Actual");

        // Spending blocks (rows 3..26, totals on row 27).
        set(s, 2, 4, "Gas"); set(s, 2, 7, 2500.0); set(s, 2, 8, 2500.0);
        set(s, 3, 4, "Necessary");
        set(s, 4, 4, "Music app"); set(s, 4, 7, 150.0); set(s, 4, 8, 150.0);
        // Laptop 30,000 over 3 months at 0.5 % per month, with the sheet's cached value deliberately wrong.
        set(s, 5, 4, "Laptop"); set(s, 5, 5, 0.005); set(s, 5, 6, laptopPeriod); set(s, 5, 7, 30000.0);
        set(s, 5, 8, 9999.0);
        set(s, 6, 4, "Refund"); set(s, 6, 7, -100.0); set(s, 6, 8, -100.0);
        set(s, 26, 6, "Total");
        set(s, 2, 9, "Book shop"); set(s, 2, 12, 640.0); set(s, 2, 13, 640.0);
        set(s, 26, 11, "Total");
        set(s, 2, 14, "Noodle bar"); set(s, 2, 17, 180.0); set(s, 2, 18, 180.0);
        set(s, 3, 14, "Cinema"); set(s, 3, 17, 260.0); set(s, 3, 18, 260.0);
        set(s, 26, 16, "Total");
    }

    private static void investment(Sheet s, CellStyle dateStyle) {
        set(s, 10, 0, "SAMPLE FUND");
        set(s, 11, 0, "Date"); set(s, 11, 1, "Funding Amt."); set(s, 11, 3, "NAV (Bath)"); set(s, 11, 4, "Unit Amt.");
        date(s, 12, 0, LocalDate.of(2026, 2, 3), dateStyle);
        set(s, 12, 1, 5000.0); set(s, 12, 3, 12.5); set(s, 12, 4, 400.0);

        set(s, 20, 0, "ETF");
        set(s, 21, 0, "ABC");
        set(s, 22, 0, "Date"); set(s, 22, 1, "Transaction (Buy / Sell)"); set(s, 22, 2, "Funding Amt.");
        set(s, 22, 3, "Exchange USD to THB"); set(s, 22, 7, "Execute price (USD)"); set(s, 22, 8, "Shares Amt.");
        // Header cells with line breaks, as in the real sheet.
        set(s, 22, 11, "Fee"); set(s, 22, 16, "Dividend\n(Date)"); set(s, 22, 17, "Dividend\n(USD)");
        set(s, 22, 18, "With Holding\nTAX (USD)");
        date(s, 23, 0, LocalDate.of(2026, 2, 10), dateStyle);
        set(s, 23, 1, "Buy"); set(s, 23, 2, 200.0); set(s, 23, 3, 34.0); set(s, 23, 7, 100.0); set(s, 23, 8, 2.0);
        set(s, 23, 11, 0.5);
        date(s, 23, 16, LocalDate.of(2026, 3, 1), dateStyle); set(s, 23, 17, 1.2); set(s, 23, 18, 0.18);
        date(s, 24, 0, LocalDate.of(2026, 3, 15), dateStyle);
        set(s, 24, 1, "Sell"); set(s, 24, 7, 110.0); set(s, 24, 8, 1.0); set(s, 24, 11, 0.03);
    }

    private static void tax(Sheet s) {
        set(s, 1, 0, "Salary (Month)"); set(s, 1, 1, 20000.0);
        set(s, 2, 0, "Bonus"); set(s, 2, 1, 40000.0);
        set(s, 5, 1, "TAX Reduction (Bath)");
        set(s, 6, 0, "ค่าใช้จ่าย"); set(s, 6, 1, 100000.0);
        set(s, 7, 0, "ค่าลดหย่อนส่วนตัว"); set(s, 7, 1, 60000.0);
        set(s, 8, 0, "กองทุน RMF"); set(s, 8, 1, 0.0);
        set(s, 16, 0, "Total TAX Reduce");
    }

    private static Row row(Sheet s, int r) {
        Row row = s.getRow(r);
        return row != null ? row : s.createRow(r);
    }

    private static void set(Sheet s, int r, int c, String v) {
        row(s, r).createCell(c).setCellValue(v);
    }

    private static void set(Sheet s, int r, int c, double v) {
        row(s, r).createCell(c).setCellValue(v);
    }

    private static void date(Sheet s, int r, int c, LocalDate d, CellStyle style) {
        Cell cell = row(s, r).createCell(c);
        cell.setCellValue(d);
        cell.setCellStyle(style);
    }
}
