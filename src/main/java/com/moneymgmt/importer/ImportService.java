package com.moneymgmt.importer;

import com.moneymgmt.domain.*;
import com.moneymgmt.importer.ImportPlan.*;
import com.moneymgmt.repo.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saves a confirmed {@link ImportPlan}. Importing again first removes everything the previous import created. */
@Service
public class ImportService {

    public record Result(int transactions, int installments, int recurring, int budgetRules, int assets,
                         int taxYears, int skipped) {
    }

    private final EntityManager em;
    private final CategoryRepository categories;
    private final BudgetRuleRepository budgetRules;
    private final TaxYearRepository taxYears;
    private final ImportBatchRepository batches;

    public ImportService(EntityManager em, CategoryRepository categories, BudgetRuleRepository budgetRules,
                         TaxYearRepository taxYears, ImportBatchRepository batches) {
        this.em = em;
        this.categories = categories;
        this.budgetRules = budgetRules;
        this.taxYears = taxYears;
        this.batches = batches;
    }

    @Transactional
    public Result save(ImportPlan plan, String sourceName) {
        // Remove the previous import; the database cascades to every row linked to a batch.
        em.createQuery("delete from ImportBatch").executeUpdate();
        em.flush();
        em.clear();

        ImportBatch batch = batches.save(new ImportBatch(sourceName, LocalDateTime.now()));
        Map<String, Category> cats = new HashMap<>();
        int skipped = 0;

        Map<String, Installment> installments = new HashMap<>();
        for (InstallmentLine line : plan.installments()) {
            Installment i = new Installment(line.item(), line.fullPrice(), line.monthlyAmount(), line.months(),
                    line.startMonth());
            i.setInterestPercent(line.interestPercent());
            i.setPaidCount(line.paidCount());
            i.setCategory(category(line.category(), cats));
            i.setImportBatch(batch);
            em.persist(i);
            installments.put(line.key(), i);
        }

        for (TxLine line : plan.transactions()) {
            Transaction t = new Transaction(line.date(), line.type(), line.amount(), category(line.category(), cats),
                    line.description());
            t.setNote(line.note());
            t.setIncentivePercent(line.incentivePercent());
            if (line.installmentKey() != null) {
                t.setInstallment(installments.get(line.installmentKey()));
                t.setInstallmentNo(line.installmentNo());
            }
            t.setImportBatch(batch);
            em.persist(t);
        }

        for (RecurringLine line : plan.recurring()) {
            RecurringItem item = new RecurringItem(line.name(), line.amount(), category(line.category(), cats));
            item.setImportBatch(batch);
            em.persist(item);
        }

        int rules = 0;
        for (BudgetLine line : plan.budgetRules()) {
            var existing = budgetRules.findByMonthAndKindAndGroup(line.month(), BudgetKind.ACTUAL, line.group());
            if (existing.isPresent()) {
                // Keep rules the owner (or the starter data) already has for that month.
                if (existing.get().getPercent() == null
                        || existing.get().getPercent().compareTo(line.percent()) != 0) {
                    skipped++;
                }
                continue;
            }
            BudgetRule rule = new BudgetRule(line.month(), BudgetKind.ACTUAL, line.group(), line.percent(), null);
            rule.setImportBatch(batch);
            em.persist(rule);
            rules++;
        }

        int assets = 0;
        for (AssetLine line : plan.assets()) {
            Asset asset = new Asset(line.name(), line.type(), line.currency());
            asset.setCurrentPrice(line.currentPrice());
            asset.setPriceDate(line.priceDate());
            asset.setImportBatch(batch);
            em.persist(asset);
            for (TradeLine t : line.trades()) {
                em.persist(new AssetTrade(asset, t.date(), t.side(), t.amount(), t.currency(), t.fxRate(),
                        t.pricePerUnit(), t.units(), t.fee()));
            }
            for (DividendLine d : line.dividends()) {
                em.persist(new Dividend(asset, d.date(), d.amount(), d.withholdingTax(), d.currency(), null));
            }
            assets++;
        }

        int years = 0;
        for (TaxLine line : plan.taxYears()) {
            if (taxYears.findByYear(line.year()).isPresent()) {
                skipped++;
                continue;
            }
            TaxYear year = new TaxYear(line.year());
            year.setMonthlySalary(line.monthlySalary());
            year.setBonus(line.bonus());
            year.setImportBatch(batch);
            line.deductions().forEach(d -> year.addDeduction(new TaxDeduction(d.code(), d.label(), d.amount())));
            em.persist(year);
            years++;
        }

        Result result = new Result(plan.transactions().size(), plan.installments().size(), plan.recurring().size(),
                rules, assets, years, skipped);
        batch.setSummary(result.transactions() + "," + result.installments() + "," + result.assets());
        return result;
    }

    private Category category(CatRef ref, Map<String, Category> cache) {
        return cache.computeIfAbsent(ref.code(), code -> categories.findByCode(code).orElseGet(() -> {
            Category c = new Category(code, ref.nameEn(), ref.nameTh(), ref.type(), ref.group());
            c.setNeedsReview(ref.needsReview());
            c.setSortOrder(900);
            return categories.save(c);
        }));
    }
}
