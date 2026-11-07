package com.moneymgmt.web;

import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.DataExportService;
import com.moneymgmt.service.ReportService;
import com.moneymgmt.service.YearExportService;
import com.moneymgmt.util.AppTime;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Yearly report, month-to-month compare, and downloads (transactions CSV, full backup .xlsx). */
@Controller
public class ReportController {

    private final ReportService reports;
    private final DataExportService export;
    private final TransactionRepository transactions;
    private final MessageSource messages;
    private final com.moneymgmt.service.YearExportService yearExport;

    public ReportController(ReportService reports, DataExportService export, TransactionRepository transactions,
                            MessageSource messages, com.moneymgmt.service.YearExportService yearExport) {
        this.yearExport = yearExport;
        this.reports = reports;
        this.export = export;
        this.transactions = transactions;
        this.messages = messages;
    }

    @GetMapping("/reports")
    public String index(@RequestParam(required = false) Integer year, @RequestParam(required = false) String a,
                        @RequestParam(required = false) String b, Model model) {
        int thisYear = AppTime.thisMonth().getYear();
        List<Integer> years = years();
        int y = year != null && year >= 1990 && year <= thisYear + 10 ? year : thisYear;
        if (!years.contains(y)) {
            years.add(0, y);
            years.sort(java.util.Comparator.reverseOrder());
        }
        YearMonth mb = AppTime.parseMonth(b);
        if (mb == null) {
            mb = AppTime.thisMonth();
        }
        YearMonth ma = AppTime.parseMonth(a);
        if (ma == null) {
            ma = mb.minusMonths(1);
        }
        model.addAttribute("years", years);
        model.addAttribute("year", y);
        model.addAttribute("report", reports.year(y));
        model.addAttribute("groups", ReportService.GROUPS);
        model.addAttribute("compare", reports.compare(ma, mb));
        model.addAttribute("monthA", ma);
        model.addAttribute("monthB", mb);
        return "reports/index";
    }

    /** Income, outcome and remaining per month of a year, for the chart. */
    @GetMapping("/api/reports/year")
    @ResponseBody
    public Map<String, Object> yearChart(@RequestParam int year, Locale locale) {
        var r = reports.year(year);
        List<Integer> months = new ArrayList<>();
        List<BigDecimal> income = new ArrayList<>();
        List<BigDecimal> outcome = new ArrayList<>();
        List<BigDecimal> saved = new ArrayList<>();
        List<BigDecimal> remaining = new ArrayList<>();
        for (var row : r.rows()) {
            months.add(row.month().getMonthValue());
            income.add(row.summary().income());
            outcome.add(row.summary().expense());
            saved.add(row.summary().transfer());
            remaining.add(row.summary().remaining());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", Map.of("income", msg("rep.income", locale), "outcome", msg("rep.outcome", locale),
                "saved", msg("rep.savedInvested", locale), "remaining", msg("rep.remaining", locale),
                "noData", msg("rep.noData", locale)));
        data.put("year", year);
        data.put("months", months);
        data.put("income", income);
        data.put("outcome", outcome);
        data.put("saved", saved);
        data.put("remaining", remaining);
        return data;
    }

    /** Transactions of a year (or all of them) as a CSV file. */
    @GetMapping("/reports/export/transactions.csv")
    public void transactionsCsv(@RequestParam(required = false) Integer year, HttpServletResponse response)
            throws IOException {
        LocalDate from = year == null ? LocalDate.of(1900, 1, 1) : LocalDate.of(year, 1, 1);
        LocalDate to = year == null ? LocalDate.of(9999, 12, 31) : LocalDate.of(year, 12, 31);
        String name = "transactions-" + (year == null ? "all" : year) + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString());
        export.transactionsCsv(from, to, response.getOutputStream());
    }

    /** One year of transactions in Excel: a Summary sheet and one sheet per month. */
    @GetMapping("/reports/export/year.xlsx")
    public ResponseEntity<byte[]> yearXlsx(@RequestParam int year, Locale locale) throws IOException {
        return ExportApiController.xlsx(YearExportService.fileName(year), yearExport.yearXlsx(year, locale));
    }

    /** Every table of the app in one Excel file (a backup you can keep). */
    @GetMapping("/reports/export/backup.xlsx")
    public ResponseEntity<byte[]> backup() throws IOException {
        String name = "money-backup-" + AppTime.today() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(export.backupXlsx());
    }

    /** From the year of the first entry to this year (newest first). */
    private List<Integer> years() {
        int thisYear = AppTime.thisMonth().getYear();
        LocalDate first = transactions.firstDate();
        int start = first == null ? thisYear : Math.min(first.getYear(), thisYear);
        List<Integer> out = new ArrayList<>();
        for (int y = thisYear; y >= start; y--) {
            out.add(y);
        }
        return out;
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }
}
