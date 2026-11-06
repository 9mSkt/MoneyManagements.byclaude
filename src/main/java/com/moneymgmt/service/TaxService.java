package com.moneymgmt.service;

import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TaxBracket;
import com.moneymgmt.domain.TaxDeduction;
import com.moneymgmt.domain.TaxYear;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryMonthTotal;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TaxBracketRepository;
import com.moneymgmt.repo.TaxYearRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.service.TaxCalculator.Bracket;
import com.moneymgmt.service.TaxCalculator.Inputs;
import com.moneymgmt.service.TaxCalculator.Result;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The yearly tax plan: employment income recorded in Transactions plus an estimate for the months still to come,
 * social security and PVD from the fixed costs, the deductions typed on the tax page, and the tax paid.
 *
 * <p>The owner files and pays the tax themselves in March–April of the next year and records it as an
 * "Income tax" expense, so tax paid for year Y is the Income tax recorded from January to June of Y+1.
 */
@Service
public class TaxService {

    /** Income paid every month: months not recorded yet are estimated. */
    static final Set<String> MONTHLY_INCOME = Set.of("SALARY", "RENT_INCOME", "PHONE_ALLOWANCE");

    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final TaxYearRepository taxYears;
    private final TaxBracketRepository brackets;

    public TaxService(TransactionRepository transactions, CategoryRepository categories,
                      TaxYearRepository taxYears, TaxBracketRepository brackets) {
        this.transactions = transactions;
        this.categories = categories;
        this.taxYears = taxYears;
        this.brackets = brackets;
    }

    /** One income category for the year: recorded so far plus the estimate for the rest. */
    public record IncomeLine(Category category, BigDecimal recorded, BigDecimal estimate, int estimatedMonths,
                             BigDecimal perMonth) {
        public BigDecimal total() {
            return recorded.add(estimate);
        }
    }

    /** Social security or PVD: recorded so far plus the latest month's amount for the months still to come. */
    public record Paid(BigDecimal recorded, BigDecimal estimate, int estimatedMonths, BigDecimal perMonth) {
        public BigDecimal total() {
            return recorded.add(estimate);
        }
    }

    /** A ready-made suggestion: buy this much RMF / ThaiESG, save this much tax. */
    public record Suggestion(BigDecimal amount, BigDecimal rmf, BigDecimal esg, BigDecimal saving,
                             BigDecimal newRate) {
    }

    /** The owner's own numbers for a year (estimates and typed deductions). */
    public record Settings(BigDecimal salaryEstimate, BigDecimal bonusExpected, BigDecimal otherExpected,
                           Map<String, BigDecimal> typed) {
    }

    public record Plan(int year, boolean saved, Settings settings, List<IncomeLine> income,
                       BigDecimal bonusRecorded, BigDecimal bonusStillExpected, Paid socialSecurity, Paid pvd,
                       Inputs inputs, Result result, List<Bracket> brackets, BigDecimal paid,
                       BigDecimal toPay, BigDecimal setAsidePerMonth, int setAsideMonths,
                       Suggestion lowerBracket, Suggestion fullRoom) {

        public boolean hasIncome() {
            return result.income().signum() > 0;
        }

        public boolean anyEstimate() {
            return income.stream().anyMatch(l -> l.estimate().signum() > 0)
                    || socialSecurity.estimate().signum() > 0 || pvd.estimate().signum() > 0;
        }
    }

    // ---------------------------------------------------------------- plan

    @Transactional(readOnly = true)
    public Plan plan(int year) {
        Settings s = settings(year);
        Map<Long, Category> cats = categories.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        // category code -> month -> amount
        Map<String, TreeMap<Integer, BigDecimal>> byCode = new LinkedHashMap<>();
        for (CategoryMonthTotal t : transactions.totalsByMonthAndCategory(LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31))) {
            Category c = cats.get(t.categoryId());
            if (c == null) {
                continue;
            }
            boolean income = t.type() == TxType.INCOME;
            boolean deduction = t.type() == TxType.EXPENSE
                    && ("SOCIAL_SECURITY".equals(c.getCode()) || "PVD".equals(c.getCode()));
            if (income || deduction) {
                byCode.computeIfAbsent(c.getCode(), k -> new TreeMap<>()).merge(t.month(), t.amount(), BigDecimal::add);
            }
        }
        List<Integer> open = openMonths(year);

        Map<String, Category> byCodeCat = cats.values().stream()
                .collect(Collectors.toMap(Category::getCode, Function.identity(), (a, b) -> a));
        List<IncomeLine> income = new ArrayList<>();
        BigDecimal bonusRecorded = Money.zero();
        BigDecimal bonusStill = Money.zero();
        for (Category c : cats.values().stream().filter(c -> c.getType() == TxType.INCOME)
                .sorted(Comparator.comparingInt(Category::getSortOrder)).toList()) {
            TreeMap<Integer, BigDecimal> months = byCode.getOrDefault(c.getCode(), new TreeMap<>());
            BigDecimal recorded = sum(months);
            BigDecimal perMonth = Money.zero();
            int estMonths = 0;
            BigDecimal estimate = Money.zero();
            if (MONTHLY_INCOME.contains(c.getCode())) {
                perMonth = "SALARY".equals(c.getCode()) ? s.salaryEstimate() : latest(months);
                if (perMonth.signum() > 0) {
                    estMonths = (int) open.stream().filter(m -> !months.containsKey(m)).count();
                    estimate = Money.scale(perMonth.multiply(BigDecimal.valueOf(estMonths)));
                }
            } else if ("BONUS".equals(c.getCode())) {
                bonusRecorded = recorded;
                bonusStill = Money.scale(s.bonusExpected().subtract(recorded).max(BigDecimal.ZERO));
                estimate = bonusStill;
            } else if ("OTHER_INCOME".equals(c.getCode())) {
                estimate = s.otherExpected();
            }
            if (recorded.signum() != 0 || estimate.signum() != 0) {
                income.add(new IncomeLine(c, recorded, estimate, estMonths, perMonth));
            }
        }
        if (!byCodeCat.containsKey("OTHER_INCOME") && s.otherExpected().signum() > 0) {
            income.add(new IncomeLine(null, Money.zero(), s.otherExpected(), 0, Money.zero()));
        }

        Paid ss = paid(byCode.getOrDefault("SOCIAL_SECURITY", new TreeMap<>()), open);
        Paid pvd = paid(byCode.getOrDefault("PVD", new TreeMap<>()), open);
        BigDecimal totalIncome = income.stream().map(IncomeLine::total).reduce(Money.zero(), BigDecimal::add);
        BigDecimal salary = income.stream().filter(l -> l.category() != null && "SALARY".equals(l.category().getCode()))
                .map(IncomeLine::total).reduce(Money.zero(), BigDecimal::add);

        Inputs inputs = new Inputs(totalIncome, salary, ss.total(), pvd.total(), s.typed());
        List<Bracket> br = brackets();
        Result result = TaxCalculator.compute(inputs, br);

        BigDecimal paidTax = taxPaid(year, cats);
        BigDecimal toPay = Money.scale(result.tax().subtract(paidTax));
        int months = monthsToFiling(year);
        BigDecimal setAside = months > 0 && toPay.signum() > 0
                ? toPay.divide(BigDecimal.valueOf(months), Money.SCALE, RoundingMode.UP) : null;

        return new Plan(year, taxYears.findByYear(year).isPresent(), s, List.copyOf(income), bonusRecorded,
                bonusStill, ss, pvd, inputs, result, br, paidTax, toPay, setAside, months,
                lowerBracket(inputs, result, br), fullRoom(inputs, result, br));
    }

    /** What-if: tax with this much more RMF and ThaiESG. */
    public Result withExtra(Plan p, BigDecimal extraRmf, BigDecimal extraEsg) {
        return TaxCalculator.compute(p.inputs().plus(extraRmf, extraEsg), p.brackets());
    }

    /** Buying enough to bring the net income down to the start of the current bracket. */
    private static Suggestion lowerBracket(Inputs in, Result r, List<Bracket> br) {
        var current = r.currentBracket();
        if (current.bracket().rate().signum() == 0 || r.fundRoom().signum() == 0) {
            return null;
        }
        BigDecimal need = r.net().subtract(current.bracket().from());
        if (need.signum() <= 0 || need.compareTo(r.fundRoom()) > 0) {
            return null;
        }
        return suggestion(need, in, r, br);
    }

    private static Suggestion fullRoom(Inputs in, Result r, List<Bracket> br) {
        if (r.fundRoom().signum() == 0 || r.tax().signum() == 0) {
            return null;
        }
        return suggestion(r.fundRoom(), in, r, br);
    }

    public static Suggestion suggestion(BigDecimal amount, Inputs in, Result r, List<Bracket> br) {
        BigDecimal[] s = TaxCalculator.split(amount, r);
        Result after = TaxCalculator.compute(in.plus(s[0], s[1]), br);
        return new Suggestion(Money.scale(s[0].add(s[1])), s[0], s[1], r.tax().subtract(after.tax()),
                after.marginalRate());
    }

    // ---------------------------------------------------------------- settings

    /** The owner's numbers for a year; a year not saved yet starts from last year's deductions. */
    @Transactional(readOnly = true)
    public Settings settings(int year) {
        Optional<TaxYear> saved = taxYears.findByYear(year);
        TaxYear base = saved.orElseGet(() -> taxYears.findByYear(year - 1).orElse(null));
        Map<String, BigDecimal> typed = new LinkedHashMap<>();
        TaxCalculator.TYPED.forEach(c -> typed.put(c, Money.zero()));
        if (base != null) {
            for (TaxDeduction d : base.getDeductions()) {
                String code = typed.containsKey(d.getCode()) ? d.getCode() : ignoredCode(d.getCode()) ? null : "OTHER";
                if (code != null) {
                    typed.merge(code, Money.scale(d.getAmount()), BigDecimal::add);
                }
            }
        }
        BigDecimal salary = saved.map(TaxYear::getMonthlySalary).filter(v -> v.signum() > 0)
                .orElseGet(() -> latestSalary(year));
        BigDecimal bonus = saved.map(TaxYear::getBonus).orElse(Money.zero());
        BigDecimal other = saved.map(TaxYear::getOtherIncome).orElse(Money.zero());
        return new Settings(Money.scale(salary), Money.scale(bonus), Money.scale(other), typed);
    }

    /** Worked out by law or taken from Transactions, so rows with these codes (from the sheet) are not used. */
    private static boolean ignoredCode(String code) {
        return Set.of("EXPENSE", "PERSONAL", "SOCIAL_SECURITY", "PVD").contains(code);
    }

    @Transactional
    public void save(int year, Settings s) {
        TaxYear y = taxYears.findByYear(year).orElseGet(() -> new TaxYear(year));
        y.setMonthlySalary(Money.scale(s.salaryEstimate()));
        y.setBonus(Money.scale(s.bonusExpected()));
        y.setOtherIncome(Money.scale(s.otherExpected()));
        // Typed deductions are replaced; rows for amounts worked out by the app (from the sheet) are kept.
        y.getDeductions().removeIf(d -> !ignoredCode(d.getCode()));
        for (String code : TaxCalculator.TYPED) {
            BigDecimal v = s.typed().get(code);
            if (v != null && v.signum() > 0) {
                y.addDeduction(new TaxDeduction(code, null, Money.scale(v)));
            }
        }
        taxYears.save(y);
    }

    // ---------------------------------------------------------------- brackets

    @Transactional(readOnly = true)
    public List<Bracket> brackets() {
        return brackets.findAllByOrderByIncomeFromAsc().stream()
                .map(b -> new Bracket(b.getIncomeFrom(), b.getIncomeTo(), b.getRate())).toList();
    }

    /** Replaces the brackets (the law changed). Rows must already be checked: ascending and joined up. */
    @Transactional
    public void saveBrackets(List<Bracket> rows) {
        List<TaxBracket> existing = brackets.findAllByOrderByIncomeFromAsc();
        for (int i = 0; i < rows.size(); i++) {
            Bracket r = rows.get(i);
            if (i < existing.size()) {
                TaxBracket b = existing.get(i);
                b.setIncomeFrom(r.from());
                b.setIncomeTo(r.to());
                b.setRate(r.rate());
            } else {
                brackets.save(new TaxBracket(r.from(), r.to(), r.rate()));
            }
        }
        if (existing.size() > rows.size()) {
            brackets.deleteAll(existing.subList(rows.size(), existing.size()));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Months of the year that may still get income: none for past years, from this month on for this year. */
    static List<Integer> openMonths(int year) {
        YearMonth now = AppTime.thisMonth();
        List<Integer> out = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            if (!YearMonth.of(year, m).isBefore(now)) {
                out.add(m);
            }
        }
        return out;
    }

    /** Months from now until the end of March of the next year (the filing month), counting this month. */
    static int monthsToFiling(int year) {
        YearMonth now = AppTime.thisMonth();
        YearMonth filing = YearMonth.of(year + 1, 3);
        YearMonth from = now.isBefore(YearMonth.of(year, 1)) ? YearMonth.of(year, 1) : now;
        if (from.isAfter(filing)) {
            return 0;
        }
        return (int) ChronoUnit.MONTHS.between(from, filing) + 1;
    }

    private BigDecimal taxPaid(int year, Map<Long, Category> cats) {
        return transactions.totalsByMonthAndCategory(LocalDate.of(year + 1, 1, 1), LocalDate.of(year + 1, 6, 30))
                .stream()
                .filter(t -> t.type() == TxType.EXPENSE && cats.containsKey(t.categoryId())
                        && "INCOME_TAX".equals(cats.get(t.categoryId()).getCode()))
                .map(CategoryMonthTotal::amount).reduce(Money.zero(), BigDecimal::add);
    }

    /** The latest salary month recorded in this year or the year before. */
    private BigDecimal latestSalary(int year) {
        for (int y = year; y >= year - 1; y--) {
            TreeMap<Integer, BigDecimal> months = new TreeMap<>();
            for (var t : transactions.findByCategoryCodeAndDateBetweenOrderByDateAscIdAsc("SALARY",
                    LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))) {
                months.merge(t.getDate().getMonthValue(), t.getAmount(), BigDecimal::add);
            }
            if (!months.isEmpty()) {
                return latest(months);
            }
        }
        return Money.zero();
    }

    private static Paid paid(TreeMap<Integer, BigDecimal> months, List<Integer> open) {
        BigDecimal perMonth = latest(months);
        int est = perMonth.signum() > 0 ? (int) open.stream().filter(m -> !months.containsKey(m)).count() : 0;
        return new Paid(sum(months), Money.scale(perMonth.multiply(BigDecimal.valueOf(est))), est, perMonth);
    }

    private static BigDecimal latest(TreeMap<Integer, BigDecimal> months) {
        return months.isEmpty() ? Money.zero() : Money.scale(months.lastEntry().getValue());
    }

    private static BigDecimal sum(Map<Integer, BigDecimal> months) {
        return Money.scale(months.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
