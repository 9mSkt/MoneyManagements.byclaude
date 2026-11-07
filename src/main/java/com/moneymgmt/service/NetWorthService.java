package com.moneymgmt.service;

import com.moneymgmt.domain.Account;
import com.moneymgmt.domain.AccountBalance;
import com.moneymgmt.domain.AccountType;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.repo.AccountBalanceRepository;
import com.moneymgmt.repo.AccountRepository;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Net worth = money in wallets, banks and investment accounts (balances typed in by the owner)
 * + the investment portfolio (worked out from trades and prices)
 * − credit card balances owed − installment debt still to pay.
 */
@Service
public class NetWorthService {

    /** An account with its latest balance. */
    public record AccountRow(Account account, BigDecimal balance, LocalDate balanceDate) {
        public boolean isDebt() {
            return account.getType() == AccountType.CREDIT;
        }
    }

    public record NetWorth(List<AccountRow> accounts, BigDecimal cash, BigDecimal credit, BigDecimal portfolio,
                           BigDecimal installmentDebt) {
        public BigDecimal assets() {
            return cash.add(portfolio);
        }

        public BigDecimal debts() {
            return credit.add(installmentDebt);
        }

        public BigDecimal total() {
            return assets().subtract(debts());
        }
    }

    public record Point(YearMonth month, BigDecimal cash, BigDecimal portfolio, BigDecimal debts) {
        public BigDecimal total() {
            return cash.add(portfolio).subtract(debts);
        }
    }

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final InstallmentRepository installments;
    private final PortfolioService portfolio;

    public NetWorthService(AccountRepository accounts, AccountBalanceRepository balances,
                           InstallmentRepository installments, PortfolioService portfolio) {
        this.accounts = accounts;
        this.balances = balances;
        this.installments = installments;
        this.portfolio = portfolio;
    }

    @Transactional(readOnly = true)
    public NetWorth now() {
        Map<Long, AccountBalance> latest = new HashMap<>();
        for (AccountBalance b : balances.findAllWithAccount()) {   // date order: the last one wins
            latest.put(b.getAccount().getId(), b);
        }
        List<AccountRow> rows = new ArrayList<>();
        BigDecimal cash = Money.zero();
        BigDecimal credit = Money.zero();
        for (Account a : accounts.findAllByOrderByActiveDescTypeAscNameAsc()) {
            AccountBalance b = latest.get(a.getId());
            BigDecimal balance = b != null ? b.getBalance() : a.getOpeningBalance();
            rows.add(new AccountRow(a, Money.scale(balance), b != null ? b.getDate() : null));
            if (!a.isActive()) {
                continue;
            }
            if (a.getType() == AccountType.CREDIT) {
                credit = credit.add(balance);
            } else {
                cash = cash.add(balance);
            }
        }
        BigDecimal value = portfolio.portfolio().valueThb();
        BigDecimal debt = installments.findAll().stream().filter(i -> !i.isFinished())
                .map(Installment::getRemainingDebt).reduce(Money.zero(), BigDecimal::add);
        return new NetWorth(rows, Money.scale(cash), Money.scale(credit), Money.scale(value == null ? Money.zero() : value),
                Money.scale(debt));
    }

    /**
     * Net worth at the end of each of the last {@code months} months (this month = today). An account counts
     * from its first balance; before that it is left out. Installment debt assumes payments on schedule.
     */
    @Transactional(readOnly = true)
    public List<Point> history(int months) {
        YearMonth last = AppTime.thisMonth();
        YearMonth first = last.minusMonths(months - 1L);

        Map<Long, TreeMap<LocalDate, BigDecimal>> perAccount = new HashMap<>();
        Map<Long, Account> accountById = new HashMap<>();
        for (AccountBalance b : balances.findAllWithAccount()) {
            perAccount.computeIfAbsent(b.getAccount().getId(), k -> new TreeMap<>()).put(b.getDate(), b.getBalance());
            accountById.put(b.getAccount().getId(), b.getAccount());
        }
        Map<YearMonth, BigDecimal> portfolioByMonth = new HashMap<>();
        for (var p : portfolio.valueOverTime()) {
            portfolioByMonth.put(p.month(), p.value());
        }
        List<Installment> plans = installments.findAll();
        NetWorth current = now();

        List<Point> out = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDate end = m.equals(last) ? AppTime.today() : m.atEndOfMonth();
            BigDecimal cash = Money.zero();
            BigDecimal credit = Money.zero();
            for (var e : perAccount.entrySet()) {
                Account a = accountById.get(e.getKey());
                if (!a.isActive()) {
                    continue;
                }
                var known = e.getValue().floorEntry(end);
                if (known == null) {
                    continue;
                }
                if (a.getType() == AccountType.CREDIT) {
                    credit = credit.add(known.getValue());
                } else {
                    cash = cash.add(known.getValue());
                }
            }
            BigDecimal debt;
            if (m.equals(last)) {
                debt = current.installmentDebt();
                cash = current.cash();
                credit = current.credit();
            } else {
                debt = Money.zero();
                for (Installment i : plans) {
                    debt = debt.add(debtAt(i, m));
                }
            }
            BigDecimal value = m.equals(last) ? current.portfolio() : portfolioByMonth.getOrDefault(m, Money.zero());
            out.add(new Point(m, Money.scale(cash), Money.scale(value), Money.scale(credit.add(debt))));
        }
        return out;
    }

    /** Debt left on a plan after the payment of {@code month} (on schedule); 0 before the plan starts. */
    public static BigDecimal debtAt(Installment plan, YearMonth month) {
        int no = plan.paymentNoIn(month);
        if (no < 1) {
            return Money.zero();
        }
        int left = Math.max(plan.getMonths() - no, 0);
        return Money.scale(plan.getMonthlyAmount().multiply(BigDecimal.valueOf(left)));
    }

    /** Saves a balance for a date (replaces one already typed for that date). */
    @Transactional
    public void recordBalance(Account account, LocalDate date, BigDecimal balance) {
        AccountBalance b = balances.findByAccountAndDate(account, date).orElse(null);
        if (b == null) {
            balances.save(new AccountBalance(account, date, Money.scale(balance)));
        } else {
            b.setBalance(Money.scale(balance));
        }
    }
}
