package com.moneymgmt.importer;

import com.moneymgmt.domain.AssetType;
import com.moneymgmt.domain.BudgetGroup;
import com.moneymgmt.domain.TradeSide;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.importer.ImportPlan.*;
import com.moneymgmt.util.Money;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;

/**
 * Reads the owner's "Money Management" workbook. Sections are found by their labels (not fixed cell positions),
 * because some month tabs have extra rows (for example a Bonus income row or a Tax fixed cost).
 *
 * <p>Month tab layout: income in A:C up to "Total"; fixed costs from "Fix cost (Bath)" up to the next "Total";
 * "Plan" rows with the actual-budget %; and three spending blocks side by side (Gas + Necessary in E:I,
 * Shopping in J:N, Eat/Lifestyle in O:S), each with Item, interest rate per month, Period ("3/6"), Full Price and
 * Bath, ending at a "Total" row.
 */
public class SheetParser {

    private static final Pattern MONTH_TAB = Pattern.compile("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(\\d{2})$");
    private static final List<String> MONTHS = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep",
            "Oct", "Nov", "Dec");
    private static final Pattern PERIOD = Pattern.compile("^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$");
    private static final Pattern INCENTIVE_REF = Pattern.compile("W(\\d+)");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE_BAHT = BigDecimal.ONE;
    private static final int MAX_SCAN_ROWS = 80;

    // Spending blocks: first column of each block (Item, rate, Period, Full Price, Bath follow).
    private record Block(int itemCol, BudgetGroup startGroup) {
    }

    private static final List<Block> BLOCKS = List.of(
            new Block(4, BudgetGroup.GAS), new Block(9, BudgetGroup.SHOPPING), new Block(14, BudgetGroup.EAT));

    static final CatRef GASOLINE = known("GASOLINE", TxType.EXPENSE, BudgetGroup.GAS);
    static final CatRef NECESSARY = known("NECESSARY", TxType.EXPENSE, BudgetGroup.NECESSARY);
    static final CatRef SHOPPING = known("SHOPPING", TxType.EXPENSE, BudgetGroup.SHOPPING);
    static final CatRef EAT = known("EAT_LIFESTYLE", TxType.EXPENSE, BudgetGroup.EAT);

    private final List<MonthSummary> months = new ArrayList<>();
    private final List<TxLine> transactions = new ArrayList<>();
    private static final class InstallmentAcc {
        String item;
        BigDecimal full;
        BigDecimal monthly;
        int count;
        BigDecimal ratePct;
        CatRef cat;
        LocalDate start;
        LocalDate lastMonth;
        final Set<Integer> numbers = new HashSet<>();
        boolean repeated;
    }

    private final Map<String, InstallmentAcc> installments = new LinkedHashMap<>();
    private final Set<String> fixWarned = new HashSet<>();
    private final List<RecurringLine> recurring = new ArrayList<>();
    private final List<BudgetLine> budgetRules = new ArrayList<>();
    private final List<AssetLine> assets = new ArrayList<>();
    private final List<TaxLine> taxYears = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private final Set<String> newCategories = new LinkedHashSet<>();

    /** Payday (e.g. 28): income of a month tab is dated on that month's payday; 0 = the 1st. */
    private int payday;

    public static ImportPlan parse(InputStream in) throws IOException {
        return parse(in, 0);
    }

    /** Reads the sheet; with a payday, income of each month tab is dated on that month's real payday. */
    public static ImportPlan parse(InputStream in, int payday) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            SheetParser p = new SheetParser();
            p.payday = payday;
            return p.read(wb);
        }
    }

    ImportPlan read(Workbook wb) {
        List<Sheet> monthSheets = new ArrayList<>();
        for (Sheet sheet : wb) {
            if (MONTH_TAB.matcher(sheet.getSheetName().trim()).matches()) {
                monthSheets.add(sheet);
            }
        }
        monthSheets.sort(Comparator.comparing(s -> monthOf(s.getSheetName())));

        Map<BudgetGroup, BigDecimal> lastPlan = null;
        List<RecurringLine> latestFixed = List.of();
        for (Sheet sheet : monthSheets) {
            LocalDate month = monthOf(sheet.getSheetName());
            List<RecurringLine> fixed = new ArrayList<>();
            readMonth(sheet, month, fixed);
            latestFixed = fixed;
            Map<BudgetGroup, BigDecimal> plan = readPlan(sheet);
            if (!plan.isEmpty() && !plan.equals(lastPlan)) {
                plan.forEach((g, p) -> budgetRules.add(new BudgetLine(month, g, p)));
                lastPlan = plan;
            }
        }
        latestFixed.stream().filter(r -> !"INCOME_TAX".equals(r.category().code())).forEach(recurring::add);

        Sheet investment = wb.getSheet("Investment");
        if (investment != null) {
            readInvestments(investment);
        }
        Sheet tax = wb.getSheet("Tax");
        if (tax != null) {
            int year = monthSheets.isEmpty() ? LocalDate.now().getYear()
                    : monthOf(monthSheets.get(monthSheets.size() - 1).getSheetName()).getYear();
            readTax(tax, year);
        }

        if (!monthSheets.isEmpty()) {
            notes.add(0, new Note("import.note.months", List.of(String.valueOf(monthSheets.size()),
                    monthSheets.get(0).getSheetName(), monthSheets.get(monthSheets.size() - 1).getSheetName())));
        } else {
            notes.add(new Note("import.note.noMonths", List.of()));
        }
        newCategories.forEach(c -> notes.add(new Note("import.note.newCategory", List.of(c))));
        if (wb.getSheet("Renovate") != null) {
            notes.add(new Note("import.note.renovate", List.of()));
        }
        if (wb.getSheet("Summary Report") != null) {
            notes.add(new Note("import.note.summary", List.of()));
        }
        return new ImportPlan(List.copyOf(months), List.copyOf(transactions), installmentLines(),
                List.copyOf(recurring), List.copyOf(budgetRules), List.copyOf(assets), List.copyOf(taxYears),
                List.copyOf(notes));
    }

    // ---------------------------------------------------------------- month tabs

    private void readMonth(Sheet sheet, LocalDate month, List<RecurringLine> fixedOut) {
        String tab = sheet.getSheetName();
        BigDecimal income = Money.zero();
        BigDecimal fixedTotal = Money.zero();
        BigDecimal spending = Money.zero();
        int rows = 0;

        // Income: from row 2 down to the first "Total" in column A.
        BigDecimal salary = null;
        int r = 1;
        for (; r < MAX_SCAN_ROWS; r++) {
            String label = text(sheet, r, 0);
            if (label.equalsIgnoreCase("Total")) {
                break;
            }
            BigDecimal amount = money(sheet, r, 2);
            if (label.isEmpty() || amount == null) {
                continue;
            }
            CatRef cat = incomeCategory(label);
            if ("SALARY".equals(cat.code())) {
                salary = amount;
            }
            if (amount.signum() == 0) {
                continue;
            }
            BigDecimal incentivePct = "INCENTIVE".equals(cat.code()) ? incentivePercent(sheet, r, amount, salary) : null;
            LocalDate paid = payday > 0 ? com.moneymgmt.util.Payday.payDate(java.time.YearMonth.from(month), payday) : month;
            transactions.add(new TxLine(paid, TxType.INCOME, cat, amount, label, null, null, null, incentivePct));
            income = income.add(amount);
            rows++;
        }

        // Fixed costs: after the "Fix cost (Bath)" header down to the next "Total".
        int header = findRow(sheet, 2, "Fix cost (Bath)", r);
        if (header >= 0) {
            for (int f = header + 1; f < header + MAX_SCAN_ROWS; f++) {
                String label = text(sheet, f, 0);
                if (label.equalsIgnoreCase("Total")) {
                    break;
                }
                BigDecimal amount = money(sheet, f, 2);
                if (label.isEmpty() || amount == null || amount.signum() == 0) {
                    continue;
                }
                CatRef cat = fixedCategory(label);
                transactions.add(new TxLine(month, TxType.EXPENSE, cat, amount, label, null, null, null, null));
                fixedOut.add(new RecurringLine(label, amount, cat));
                fixedTotal = fixedTotal.add(amount);
                rows++;
            }
        }

        // Spending blocks.
        for (Block block : BLOCKS) {
            int periodCol = block.itemCol() + 2;
            int end = findRow(sheet, periodCol, "Total", 2);
            if (end < 0) {
                continue;
            }
            BudgetGroup group = block.startGroup();
            for (int s = 2; s < end; s++) {
                String item = text(sheet, s, block.itemCol());
                BigDecimal rate = number(sheet, s, block.itemCol() + 1);
                String period = text(sheet, s, periodCol);
                BigDecimal full = money(sheet, s, block.itemCol() + 3);
                BigDecimal bath = money(sheet, s, block.itemCol() + 4);
                if (block.startGroup() == BudgetGroup.GAS && item.equalsIgnoreCase("Necessary") && full == null) {
                    group = BudgetGroup.NECESSARY;
                    continue;
                }
                if (full == null && bath == null) {
                    continue;
                }
                if (item.isEmpty()) {
                    item = "-";
                }
                Matcher pm = PERIOD.matcher(period);
                Integer no = null;
                String key = null;
                BigDecimal amount = bath;
                if (pm.matches() && full != null) {
                    no = Integer.parseInt(pm.group(1));
                    int count = Integer.parseInt(pm.group(2));
                    if (count > 0) {
                        BigDecimal monthly = full.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
                        if (rate != null) {
                            monthly = monthly.add(full.multiply(rate));
                        }
                        monthly = Money.scale(monthly);
                        if ((bath == null || bath.subtract(monthly).abs().compareTo(ONE_BAHT) > 0)
                                && fixWarned.add(item + "|" + full + "|" + count)) {
                            notes.add(new Note("import.note.installmentFix", List.of(tab, item, period,
                                    bath == null ? "-" : Money.format(bath), Money.format(monthly))));
                        }
                        amount = monthly;
                        if (full.signum() > 0) {
                            key = installment(item, full, monthly, count, rate, month, no, categoryFor(group));
                        }
                    }
                } else if (amount == null) {
                    amount = full;
                }
                if (amount == null || amount.signum() == 0) {
                    continue;
                }
                if (amount.signum() < 0) {
                    notes.add(new Note("import.note.negative", List.of(tab, item, Money.format(amount))));
                }
                transactions.add(new TxLine(month, TxType.EXPENSE, categoryFor(group), amount, item,
                        no == null ? null : period.trim(), key, key == null ? null : no, null));
                spending = spending.add(amount);
                rows++;
            }
        }
        months.add(new MonthSummary(month, income, fixedTotal, spending, rows));
    }

    private String installment(String item, BigDecimal full, BigDecimal monthly, int count, BigDecimal rate,
                               LocalDate month, int no, CatRef cat) {
        String key = item.trim().toLowerCase(Locale.ROOT) + "|" + full.toPlainString() + "|" + count;
        LocalDate start = month.minusMonths(Math.max(no, 1) - 1L);
        BigDecimal ratePct = rate == null ? BigDecimal.ZERO : rate.multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
        InstallmentAcc acc = installments.computeIfAbsent(key, k -> {
            InstallmentAcc a = new InstallmentAcc();
            a.item = item;
            a.full = full;
            a.count = count;
            a.cat = cat;
            a.start = start;
            return a;
        });
        acc.monthly = monthly;
        acc.ratePct = ratePct;
        if (start.isBefore(acc.start)) {
            acc.start = start;
        }
        if (acc.lastMonth == null || month.isAfter(acc.lastMonth)) {
            acc.lastMonth = month;
        }
        if (!acc.numbers.add(no)) {
            acc.repeated = true;
        }
        return key;
    }

    /** Paid count = months from the first payment to the last month the item appears in (capped at the total). */
    private List<InstallmentLine> installmentLines() {
        List<InstallmentLine> lines = new ArrayList<>();
        for (Map.Entry<String, InstallmentAcc> e : installments.entrySet()) {
            InstallmentAcc a = e.getValue();
            long span = java.time.temporal.ChronoUnit.MONTHS.between(a.start, a.lastMonth) + 1;
            int paid = (int) Math.min(a.count, Math.max(span, 1));
            if (a.repeated) {
                notes.add(new Note("import.note.installmentRepeated",
                        List.of(a.item, String.valueOf(paid), String.valueOf(a.count))));
            }
            lines.add(new InstallmentLine(e.getKey(), a.item, a.full, a.monthly, a.count, a.ratePct, a.start, paid,
                    a.cat));
        }
        return List.copyOf(lines);
    }

    private Map<BudgetGroup, BigDecimal> readPlan(Sheet sheet) {
        Map<BudgetGroup, BigDecimal> plan = new EnumMap<>(BudgetGroup.class);
        int row = findRow(sheet, 0, "Plan", 0);
        if (row < 0) {
            return plan;
        }
        for (int r = row + 1; r < row + 8; r++) {
            String label = text(sheet, r, 0).toLowerCase(Locale.ROOT);
            BigDecimal share = number(sheet, r, 1);
            if (label.isEmpty() || label.equals("actual")) {
                break;
            }
            BudgetGroup group = switch (label) {
                case "eat", "eat/lifestyle" -> BudgetGroup.EAT;
                case "shopping" -> BudgetGroup.SHOPPING;
                case "gas", "gasoline" -> BudgetGroup.GAS;
                case "necessary" -> BudgetGroup.NECESSARY;
                default -> null;
            };
            if (group != null && share != null) {
                plan.put(group, share.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return plan;
    }

    /** Incentive % from the row the formula picks (=IF(B5=TRUE,W4,0) → V4), else amount / salary. */
    private BigDecimal incentivePercent(Sheet sheet, int row, BigDecimal amount, BigDecimal salary) {
        Cell cell = cell(sheet, row, 2);
        if (cell != null && cell.getCellType() == CellType.FORMULA) {
            Matcher m = INCENTIVE_REF.matcher(cell.getCellFormula());
            if (m.find()) {
                BigDecimal pct = number(sheet, Integer.parseInt(m.group(1)) - 1, 21);
                if (pct != null) {
                    return pct.setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return salary == null || salary.signum() == 0 ? null : Money.percent(amount, salary);
    }

    private CatRef incomeCategory(String label) {
        String l = label.trim().toLowerCase(Locale.ROOT);
        String code = switch (l) {
            case "salary" -> "SALARY";
            case "rent" -> "RENT_INCOME";
            case "phone" -> "PHONE_ALLOWANCE";
            case "incentive" -> "INCENTIVE";
            case "bonus" -> "BONUS";
            default -> null;
        };
        return code != null ? known(code, TxType.INCOME, null) : known("OTHER_INCOME", TxType.INCOME, null);
    }

    private CatRef fixedCategory(String label) {
        String l = label.trim().toLowerCase(Locale.ROOT);
        String code;
        if (l.contains("ประกันสังคม") || l.contains("social security")) {
            code = "SOCIAL_SECURITY";
        } else if (l.contains("สำรองเลี้ยงชีพ") || l.contains("pvd") || l.contains("provident")) {
            code = "PVD";
        } else if (l.startsWith("mom") && (l.contains("dept") || l.contains("debt"))) {
            code = "MOM_DEBT";
        } else if (l.equals("mom")) {
            code = "FAMILY";
        } else if (l.startsWith("phone")) {
            code = "PHONE_BILL";
        } else if (l.startsWith("internet")) {
            code = "INTERNET";
        } else if (l.startsWith("insurance")) {
            code = "INSURANCE";
        } else if (l.startsWith("electric")) {
            code = "ELECTRICITY";
        } else if (l.startsWith("water")) {
            code = "WATER";
        } else if (l.equals("car")) {
            code = "CAR_PAYMENT";
        } else if (l.equals("rent")) {
            code = "RENT";
        } else if (l.equals("tax")) {
            code = "INCOME_TAX";
        } else {
            String newCode = "FIXED_" + l.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            if (newCode.equals("FIXED_")) {
                newCode = "FIXED_" + Integer.toHexString(l.hashCode());
            }
            newCode = newCode.toUpperCase(Locale.ROOT);
            newCategories.add(label.trim());
            return new CatRef(newCode.length() > 60 ? newCode.substring(0, 60) : newCode, label.trim(), label.trim(),
                    TxType.EXPENSE, BudgetGroup.FIXED, true);
        }
        return known(code, TxType.EXPENSE, BudgetGroup.FIXED);
    }

    private static CatRef categoryFor(BudgetGroup group) {
        return switch (group) {
            case GAS -> GASOLINE;
            case NECESSARY -> NECESSARY;
            case SHOPPING -> SHOPPING;
            default -> EAT;
        };
    }

    /** A seeded category (names come from the database). */
    private static CatRef known(String code, TxType type, BudgetGroup group) {
        return new CatRef(code, code, code, type, group, false);
    }

    static LocalDate monthOf(String tabName) {
        Matcher m = MONTH_TAB.matcher(tabName.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(tabName);
        }
        return LocalDate.of(2000 + Integer.parseInt(m.group(2)), MONTHS.indexOf(m.group(1)) + 1, 1);
    }

    // ---------------------------------------------------------------- Investment tab

    private void readInvestments(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), 2000);
        for (int r = 0; r <= last; r++) {
            if (!text(sheet, r, 0).startsWith("Date")) {
                continue;
            }
            String name = "";
            for (int up = r - 1; up >= Math.max(0, r - 2) && name.isEmpty(); up--) {
                name = text(sheet, up, 0);
            }
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Integer> cols = headerColumns(sheet.getRow(r));
            Integer price = find(cols, "execute price");
            if (price != null) {
                readEtf(sheet, r, name, cols);
            } else if (find(cols, "nav") != null) {
                readFund(sheet, r, name, cols);
            }
        }
    }

    private void readFund(Sheet sheet, int headerRow, String name, Map<String, Integer> cols) {
        Integer amountCol = find(cols, "funding amt");
        Integer navCol = find(cols, "nav");
        Integer unitsCol = find(cols, "unit amt");
        List<TradeLine> trades = new ArrayList<>();
        for (int r = headerRow + 1; r < headerRow + 500; r++) {
            LocalDate date = date(sheet, r, 0);
            if (date == null) {
                break;
            }
            BigDecimal amount = money(sheet, r, amountCol);
            BigDecimal nav = number(sheet, r, navCol);
            BigDecimal units = number(sheet, r, unitsCol);
            if (amount == null || nav == null || units == null) {
                continue;
            }
            trades.add(new TradeLine(date, TradeSide.BUY, amount, "THB", null, nav, units, Money.zero()));
        }
        addAsset(name, AssetType.MUTUAL_FUND, "THB", trades, List.of());
    }

    private void readEtf(Sheet sheet, int headerRow, String name, Map<String, Integer> cols) {
        Integer sideCol = find(cols, "transaction");
        Integer amountCol = find(cols, "funding amt");
        Integer fxCol = find(cols, "exchange");
        Integer priceCol = find(cols, "execute price");
        Integer sharesCol = find(cols, "shares amt");
        Integer feeCol = find(cols, "fee");
        Integer divDateCol = find(cols, "dividend (date");
        Integer divCol = null;
        for (Map.Entry<String, Integer> e : cols.entrySet()) {
            if (e.getKey().startsWith("dividend (") && !e.getKey().startsWith("dividend (date")) {
                divCol = e.getValue();
                break;
            }
        }
        Integer whtCol = find(cols, "with holding");
        List<TradeLine> trades = new ArrayList<>();
        List<DividendLine> dividends = new ArrayList<>();
        for (int r = headerRow + 1; r < headerRow + 500; r++) {
            LocalDate date = date(sheet, r, 0);
            if (date == null) {
                break;
            }
            String sideText = text(sheet, r, sideCol).toLowerCase(Locale.ROOT);
            BigDecimal price = number(sheet, r, priceCol);
            BigDecimal shares = number(sheet, r, sharesCol);
            if (price != null && shares != null && (sideText.equals("buy") || sideText.equals("sell"))) {
                TradeSide side = sideText.equals("buy") ? TradeSide.BUY : TradeSide.SELL;
                BigDecimal amount = money(sheet, r, amountCol);
                if (amount == null) {
                    amount = Money.scale(price.multiply(shares));
                }
                BigDecimal fee = money(sheet, r, feeCol);
                trades.add(new TradeLine(date, side, amount, "USD", number(sheet, r, fxCol), price, shares,
                        fee == null ? Money.zero() : fee));
            }
            LocalDate divDate = date(sheet, r, divDateCol);
            BigDecimal div = money(sheet, r, divCol);
            if (divDate != null && div != null) {
                BigDecimal wht = money(sheet, r, whtCol);
                dividends.add(new DividendLine(divDate, div, wht == null ? Money.zero() : wht, "USD"));
            }
        }
        AssetType type = name.equalsIgnoreCase("GLD") ? AssetType.GOLD : AssetType.ETF;
        addAsset(name, type, "USD", trades, dividends);
    }

    private void addAsset(String name, AssetType type, String currency, List<TradeLine> trades,
                          List<DividendLine> dividends) {
        if (trades.isEmpty()) {
            return;
        }
        TradeLine lastTrade = trades.get(trades.size() - 1);
        assets.add(new AssetLine(name, type, currency, lastTrade.pricePerUnit(), lastTrade.date(),
                List.copyOf(trades), List.copyOf(dividends)));
    }

    private static Map<String, Integer> headerColumns(Row row) {
        Map<String, Integer> cols = new LinkedHashMap<>();
        if (row == null) {
            return cols;
        }
        for (Cell c : row) {
            // Header cells often contain line breaks ("Dividend\n(Date)"), so collapse all whitespace.
            String t = cellText(c).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                cols.putIfAbsent(t, c.getColumnIndex());
            }
        }
        return cols;
    }

    private static Integer find(Map<String, Integer> cols, String prefix) {
        for (Map.Entry<String, Integer> e : cols.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Tax tab

    private void readTax(Sheet sheet, int year) {
        BigDecimal salary = null;
        BigDecimal bonus = null;
        List<DeductionLine> deductions = new ArrayList<>();
        boolean inDeductions = false;
        for (int r = 0; r < MAX_SCAN_ROWS; r++) {
            String label = text(sheet, r, 0);
            String l = label.toLowerCase(Locale.ROOT);
            if (text(sheet, r, 1).toLowerCase(Locale.ROOT).startsWith("tax reduction")) {
                inDeductions = true;
                continue;
            }
            if (l.startsWith("total tax reduce")) {
                inDeductions = false;
                continue;
            }
            if (l.startsWith("salary")) {
                salary = money(sheet, r, 1);
            } else if (l.equals("bonus")) {
                bonus = money(sheet, r, 1);
            } else if (inDeductions && !label.isEmpty()) {
                BigDecimal amount = money(sheet, r, 1);
                if (amount != null) {
                    deductions.add(new DeductionLine(deductionCode(l), label, amount));
                }
            }
        }
        if (salary != null || !deductions.isEmpty()) {
            taxYears.add(new TaxLine(year, salary == null ? Money.zero() : salary,
                    bonus == null ? Money.zero() : bonus, List.copyOf(deductions)));
        }
    }

    private static String deductionCode(String l) {
        if (l.contains("ค่าใช่จ่าย") || l.contains("ค่าใช้จ่าย") || l.contains("expense")) {
            return "EXPENSE";
        }
        if (l.contains("ส่วนตัว") || l.contains("personal")) {
            return "PERSONAL";
        }
        if (l.contains("ประกันสังคม") || l.contains("social")) {
            return "SOCIAL_SECURITY";
        }
        if (l.contains("pvd") || l.contains("สำรอง")) {
            return "PVD";
        }
        if (l.contains("ประกันชีวิต") || l.contains("life")) {
            return "LIFE_INSURANCE";
        }
        if (l.contains("rmf")) {
            return "RMF";
        }
        if (l.contains("ssf")) {
            return "SSF";
        }
        if (l.contains("esg")) {
            return "THAI_ESG";
        }
        return "OTHER";
    }

    // ---------------------------------------------------------------- cell helpers

    private static int findRow(Sheet sheet, int col, String text, int from) {
        for (int r = from; r < from + MAX_SCAN_ROWS; r++) {
            if (text(sheet, r, col).equalsIgnoreCase(text)) {
                return r;
            }
        }
        return -1;
    }

    private static Cell cell(Sheet sheet, int row, Integer col) {
        if (col == null) {
            return null;
        }
        Row r = sheet.getRow(row);
        return r == null ? null : r.getCell(col);
    }

    private static String text(Sheet sheet, int row, Integer col) {
        return cellText(cell(sheet, row, col));
    }

    private static String cellText(Cell c) {
        if (c == null) {
            return "";
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return type == CellType.STRING ? c.getStringCellValue().trim() : "";
    }

    /** Numeric value (cached result for formulas); null for blanks, text and errors such as #REF!. */
    private static BigDecimal number(Sheet sheet, int row, Integer col) {
        Cell c = cell(sheet, row, col);
        if (c == null) {
            return null;
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (type != CellType.NUMERIC || DateUtil.isCellDateFormatted(c)) {
            return null;
        }
        double v = c.getNumericCellValue();
        return Double.isFinite(v) ? new BigDecimal(Double.toString(v)) : null;
    }

    private static BigDecimal money(Sheet sheet, int row, Integer col) {
        return Money.scale(number(sheet, row, col));
    }

    private static LocalDate date(Sheet sheet, int row, Integer col) {
        Cell c = cell(sheet, row, col);
        if (c == null) {
            return null;
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            return c.getLocalDateTimeCellValue().toLocalDate();
        }
        return null;
    }
}
