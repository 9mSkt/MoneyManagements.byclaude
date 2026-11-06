package com.moneymgmt.service;

import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thai personal income tax (PIT) for employment income (section 40(1)): deductions with their legal limits,
 * progressive brackets, and "how much RMF / ThaiESG to buy". Pure arithmetic on BigDecimal, no database.
 *
 * <p>Limits follow the rules for the 2025–2026 tax years. Social security and PVD are what was paid; the expense
 * and personal allowances are worked out by law; everything else is what the owner types.
 */
public final class TaxCalculator {

    public static final BigDecimal EXPENSE_RATE = new BigDecimal("0.50");
    public static final BigDecimal EXPENSE_MAX = Money.of(100_000);
    public static final BigDecimal PERSONAL = Money.of(60_000);
    public static final BigDecimal LIFE_MAX = Money.of(100_000);
    public static final BigDecimal HEALTH_MAX = Money.of(25_000);
    public static final BigDecimal PARENT_HEALTH_MAX = Money.of(15_000);
    /** 30,000 for each parent aged 60+ with a small income; up to 4 with a spouse's parents. */
    public static final BigDecimal PARENTS_MAX = Money.of(120_000);
    public static final BigDecimal HOME_LOAN_MAX = Money.of(100_000);
    public static final BigDecimal FUND_RATE = new BigDecimal("0.30");
    public static final BigDecimal PVD_RATE = new BigDecimal("0.15");
    /** PVD + RMF (and other retirement savings) together. */
    public static final BigDecimal RETIREMENT_MAX = Money.of(500_000);
    public static final BigDecimal ESG_MAX = Money.of(300_000);
    public static final BigDecimal DONATION_RATE = new BigDecimal("0.10");

    /** Deductions the owner types on the tax page, in display order. */
    public static final List<String> TYPED = List.of("LIFE_INSURANCE", "HEALTH_INSURANCE", "PARENT_HEALTH",
            "PARENTS", "HOME_LOAN", "RMF", "THAI_ESG", "DONATION", "OTHER");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TaxCalculator() {
    }

    /** A bracket of net income: from (exclusive) to (inclusive, null = no limit), rate in percent. */
    public record Bracket(BigDecimal from, BigDecimal to, BigDecimal rate) {
    }

    /**
     * What the tax is worked out from. {@code income} is all employment income, {@code salary} the salary part
     * (the PVD limit is 15 % of wages), {@code typed} the amounts by code from {@link #TYPED}.
     */
    public record Inputs(BigDecimal income, BigDecimal salary, BigDecimal socialSecurity, BigDecimal pvd,
                         Map<String, BigDecimal> typed) {

        public BigDecimal typed(String code) {
            BigDecimal v = typed.get(code);
            return v == null ? Money.zero() : v;
        }

        /** The same inputs with more RMF and ThaiESG bought. */
        public Inputs plus(BigDecimal extraRmf, BigDecimal extraEsg) {
            Map<String, BigDecimal> t = new LinkedHashMap<>(typed);
            t.put("RMF", typed("RMF").add(nz(extraRmf)));
            t.put("THAI_ESG", typed("THAI_ESG").add(nz(extraEsg)));
            return new Inputs(income, salary, socialSecurity, pvd, t);
        }
    }

    /** One deduction: what was claimed, what the law allows ({@code limit} null = no limit). */
    public record Deduction(String code, BigDecimal claimed, BigDecimal allowed, BigDecimal limit) {

        public boolean capped() {
            return allowed.compareTo(claimed) < 0;
        }
    }

    /** How much of the net income falls in a bracket and the tax on it. */
    public record BracketLine(Bracket bracket, BigDecimal portion, BigDecimal tax, boolean current) {

        /** Room left in this bracket before the next one starts (null for the last bracket). */
        public BigDecimal size() {
            return bracket.to() == null ? null : bracket.to().subtract(bracket.from());
        }
    }

    public record Result(BigDecimal income, List<Deduction> deductions, BigDecimal totalDeductions, BigDecimal net,
                         List<BracketLine> brackets, BigDecimal tax, BigDecimal marginalRate,
                         BigDecimal effectiveRate, BigDecimal rmfRoom, BigDecimal esgRoom) {

        public Deduction deduction(String code) {
            return deductions.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null);
        }

        /** The bracket the net income ends in. */
        public BracketLine currentBracket() {
            return brackets.stream().filter(BracketLine::current).findFirst().orElse(brackets.get(0));
        }

        public BigDecimal fundRoom() {
            return rmfRoom.add(esgRoom);
        }
    }

    // ---------------------------------------------------------------- tax

    public static Result compute(Inputs in, List<Bracket> brackets) {
        BigDecimal income = nz(in.income()).max(BigDecimal.ZERO);
        List<Deduction> list = new ArrayList<>();

        list.add(capped("EXPENSE", income.multiply(EXPENSE_RATE), EXPENSE_MAX));
        list.add(new Deduction("PERSONAL", PERSONAL, PERSONAL, null));
        list.add(new Deduction("SOCIAL_SECURITY", nz(in.socialSecurity()), nz(in.socialSecurity()), null));

        BigDecimal pvdLimit = money(nz(in.salary()).multiply(PVD_RATE)).min(RETIREMENT_MAX);
        Deduction pvd = capped("PVD", nz(in.pvd()), pvdLimit);
        list.add(pvd);

        Deduction life = capped("LIFE_INSURANCE", in.typed("LIFE_INSURANCE"), LIFE_MAX);
        list.add(life);
        list.add(capped("HEALTH_INSURANCE", in.typed("HEALTH_INSURANCE"),
                HEALTH_MAX.min(LIFE_MAX.subtract(life.allowed()))));
        list.add(capped("PARENT_HEALTH", in.typed("PARENT_HEALTH"), PARENT_HEALTH_MAX));
        list.add(capped("PARENTS", in.typed("PARENTS"), PARENTS_MAX));
        list.add(capped("HOME_LOAN", in.typed("HOME_LOAN"), HOME_LOAN_MAX));

        BigDecimal fundLimit = money(income.multiply(FUND_RATE));
        BigDecimal rmfLimit = fundLimit.min(RETIREMENT_MAX.subtract(pvd.allowed())).max(BigDecimal.ZERO);
        Deduction rmf = capped("RMF", in.typed("RMF"), rmfLimit);
        list.add(rmf);
        BigDecimal esgLimit = fundLimit.min(ESG_MAX);
        Deduction esg = capped("THAI_ESG", in.typed("THAI_ESG"), esgLimit);
        list.add(esg);
        list.add(new Deduction("OTHER", in.typed("OTHER"), in.typed("OTHER"), null));

        // Donations: up to 10 % of income after every other deduction.
        BigDecimal before = list.stream().map(Deduction::allowed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal donationLimit = money(income.subtract(before).max(BigDecimal.ZERO).multiply(DONATION_RATE));
        list.add(capped("DONATION", in.typed("DONATION"), donationLimit));

        BigDecimal total = money(list.stream().map(Deduction::allowed).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal net = money(income.subtract(total).max(BigDecimal.ZERO));
        List<BracketLine> lines = bracketLines(net, brackets);
        BigDecimal tax = money(lines.stream().map(BracketLine::tax).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal marginal = lines.stream().filter(BracketLine::current).findFirst()
                .map(l -> l.bracket().rate()).orElse(BigDecimal.ZERO);
        BigDecimal rmfRoom = money(rmfLimit.subtract(rmf.claimed()).max(BigDecimal.ZERO));
        BigDecimal esgRoom = money(esgLimit.subtract(esg.claimed()).max(BigDecimal.ZERO));
        return new Result(money(income), List.copyOf(list), total, net, lines, tax, marginal,
                Money.percent(tax, income), rmfRoom, esgRoom);
    }

    /** Tax on a net income. */
    public static BigDecimal taxOn(BigDecimal net, List<Bracket> brackets) {
        return money(bracketLines(net, brackets).stream().map(BracketLine::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static List<BracketLine> bracketLines(BigDecimal net, List<Bracket> brackets) {
        List<BracketLine> out = new ArrayList<>();
        boolean currentFound = false;
        for (int i = 0; i < brackets.size(); i++) {
            Bracket b = brackets.get(i);
            BigDecimal portion = net.subtract(b.from()).max(BigDecimal.ZERO);
            if (b.to() != null) {
                portion = portion.min(b.to().subtract(b.from()));
            }
            BigDecimal tax = money(portion.multiply(b.rate()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            boolean inside = net.compareTo(b.from()) > 0 && (b.to() == null || net.compareTo(b.to()) <= 0);
            boolean current = !currentFound && (inside || (i == 0 && net.signum() == 0));
            currentFound |= current;
            out.add(new BracketLine(b, money(portion), tax, current));
        }
        return out;
    }

    // ---------------------------------------------------------------- what if

    /** Tax saved by buying more RMF and ThaiESG (amounts over the limit save nothing). */
    public static BigDecimal saving(Inputs in, List<Bracket> brackets, BigDecimal extraRmf, BigDecimal extraEsg) {
        BigDecimal base = compute(in, brackets).tax();
        return base.subtract(compute(in.plus(extraRmf, extraEsg), brackets).tax());
    }

    /** Splits an amount between RMF (first) and ThaiESG within their rooms: [rmf, esg]. */
    public static BigDecimal[] split(BigDecimal amount, Result r) {
        BigDecimal rmf = amount.min(r.rmfRoom());
        BigDecimal esg = amount.subtract(rmf).min(r.esgRoom());
        return new BigDecimal[] {money(rmf), money(esg)};
    }

    /**
     * The smallest RMF + ThaiESG purchase (whole baht, RMF first) that saves at least {@code target} tax,
     * or null when even the full limit saves less.
     */
    public static BigDecimal neededFor(BigDecimal target, Inputs in, List<Bracket> brackets) {
        Result base = compute(in, brackets);
        if (target == null || target.signum() <= 0) {
            return Money.zero();
        }
        BigDecimal room = base.fundRoom().setScale(0, RoundingMode.FLOOR);
        if (savingFor(room, in, base, brackets).compareTo(target) < 0) {
            return null;
        }
        long lo = 0;
        long hi = room.longValueExact();
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (savingFor(BigDecimal.valueOf(mid), in, base, brackets).compareTo(target) >= 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return Money.of(hi);
    }

    private static BigDecimal savingFor(BigDecimal amount, Inputs in, Result base, List<Bracket> brackets) {
        BigDecimal[] s = split(amount, base);
        return base.tax().subtract(compute(in.plus(s[0], s[1]), brackets).tax());
    }

    // ---------------------------------------------------------------- helpers

    private static Deduction capped(String code, BigDecimal claimed, BigDecimal limit) {
        BigDecimal c = money(nz(claimed).max(BigDecimal.ZERO));
        BigDecimal l = money(limit.max(BigDecimal.ZERO));
        return new Deduction(code, c, c.min(l), l);
    }

    private static BigDecimal money(BigDecimal v) {
        return Money.scale(v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? Money.zero() : v;
    }
}
