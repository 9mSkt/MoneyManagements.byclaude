package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** The year file with one sheet per month, and the key-protected address the Google Drive script uses. Made-up data. */
@TestPropertySource(properties = "app.export-token=" + YearExportTest.TOKEN)
@Transactional
class YearExportTest extends AbstractWebTest {

    static final String TOKEN = "sample-token-0123456789abcdef0123456789abcdef";

    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    private void tx(LocalDate date, String code, String amount, String description) {
        var c = categories.findByCode(code).orElseThrow();
        transactions.save(new Transaction(date, c.getType(), Money.of(amount), c, description));
    }

    private void sampleYear() {
        tx(LocalDate.of(2025, 1, 28), "SALARY", "40000", "Sample salary");
        tx(LocalDate.of(2025, 1, 10), "EAT_LIFESTYLE", "350.50", "Sample lunch");
        tx(LocalDate.of(2025, 3, 5), "SHOPPING", "1200", "=SUM(A1)");   // looks like a formula
        tx(LocalDate.of(2025, 3, 6), "DEPOSIT", "2000", "Sample deposit");
    }

    private static Workbook read(byte[] b) throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(b));
    }

    @Test
    @WithMockUser(username = "tester")
    void oneSheetPerMonthWithTotals() throws Exception {
        sampleYear();
        byte[] xlsx = mvc.perform(get("/reports/export/year.xlsx").param("year", "2025").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("money-2025-by-month.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = read(xlsx)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);   // Summary, Jan 2025, Mar 2025 (months with entries)
            assertThat(wb.getSheetName(0)).isEqualTo("Summary");
            assertThat(wb.getSheetName(1)).isEqualTo("Jan 2025");
            assertThat(wb.getSheetName(2)).isEqualTo("Mar 2025");

            var summary = wb.getSheet("Summary");
            assertThat(summary.getRow(3).getCell(0).getStringCellValue()).isEqualTo("January");
            assertThat(summary.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(40000.0);
            assertThat(summary.getRow(15).getCell(0).getStringCellValue()).isEqualTo("Total");

            var jan = wb.getSheet("Jan 2025");
            assertThat(jan.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Date");
            assertThat(jan.getRow(3).getCell(4).getStringCellValue()).isEqualTo("Sample lunch");   // oldest first
            assertThat(jan.getRow(3).getCell(5).getNumericCellValue()).isEqualTo(350.5);
            assertThat(jan.getRow(6).getCell(5).getNumericCellValue()).isEqualTo(40000.0);   // income total
            assertThat(jan.getRow(7).getCell(5).getNumericCellValue()).isEqualTo(350.5);     // outcome total
            var mar = wb.getSheet("Mar 2025");
            assertThat(mar.getRow(3).getCell(4).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(mar.getRow(3).getCell(4).getStringCellValue()).isEqualTo("=SUM(A1)");
        }
    }

    @Test
    @WithMockUser(username = "tester")
    void thaiLabels() throws Exception {
        sampleYear();
        byte[] xlsx = mvc.perform(get("/reports/export/year.xlsx").param("year", "2025").param("lang", "th"))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = read(xlsx)) {
            assertThat(wb.getSheetName(0)).isEqualTo("สรุป");
            assertThat(wb.getSheet("Jan 2025").getRow(4).getCell(3).getStringCellValue()).isEqualTo("เงินเดือน");
        }
    }

    @Test
    @WithAnonymousUser
    void driveScriptNeedsTheKey() throws Exception {
        sampleYear();
        mvc.perform(get("/api/export/year.xlsx").param("year", "2025")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/export/year.xlsx").param("year", "2025").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        byte[] xlsx = mvc.perform(get("/api/export/year.xlsx").param("year", "2025")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = read(xlsx)) {
            assertThat(wb.getSheet("Jan 2025")).isNotNull();
        }
        // Other pages still need a sign-in.
        mvc.perform(get("/reports/export/year.xlsx").param("year", "2025").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "tester")
    void settingsShowsWhenTheScriptLastFetched() throws Exception {
        mvc.perform(get("/api/export/year.xlsx").param("year", "2025").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
        mvc.perform(get("/settings").param("lang", "en"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Last sent: the year 2025 file")));
    }
}
