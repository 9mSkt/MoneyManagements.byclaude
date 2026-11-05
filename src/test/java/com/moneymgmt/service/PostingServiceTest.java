package com.moneymgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.Installment;
import com.moneymgmt.domain.RecurringItem;
import com.moneymgmt.domain.Transaction;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.InstallmentRepository;
import com.moneymgmt.repo.RecurringItemRepository;
import com.moneymgmt.repo.TransactionRepository;
import com.moneymgmt.util.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Made-up sample items in 2032; every test is rolled back. */
@Transactional
class PostingServiceTest extends AbstractWebTest {

    @Autowired PostingService posting;
    @Autowired TransactionService txService;
    @Autowired RecurringItemRepository items;
    @Autowired InstallmentRepository plans;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;

    private Category cat(String code) {
        return categories.findByCode(code).orElseThrow();
    }

    private List<Transaction> linked(RecurringItem item, YearMonth m) {
        return transactions.findByRecurringItemInAndDateBetween(List.of(item), m.atDay(1), m.atEndOfMonth());
    }

    private List<Transaction> payments(Installment plan, YearMonth m) {
        return transactions.findByInstallmentInAndDateBetween(List.of(plan), m.atDay(1), m.atEndOfMonth());
    }

    @Test
    void recurringItemIsPostedOnceOnOrAfterItsDay() {
        RecurringItem rent = new RecurringItem("Sample rent", Money.of("8000"), cat("RENT"));
        rent.setDayOfMonth(5);
        items.save(rent);
        YearMonth m = YearMonth.of(2032, 3);

        posting.postDue(LocalDate.of(2032, 3, 4));
        assertThat(linked(rent, m)).as("not due before day 5").isEmpty();

        posting.postDue(LocalDate.of(2032, 3, 5));
        posting.postDue(LocalDate.of(2032, 3, 20));
        assertThat(linked(rent, m)).singleElement().satisfies(t -> {
            assertThat(t.getDate()).isEqualTo(LocalDate.of(2032, 3, 5));
            assertThat(t.getAmount()).isEqualByComparingTo("8000");
            assertThat(t.getType()).isEqualTo(TxType.EXPENSE);
        });
    }

    @Test
    void dayIsMovedToTheEndOfShortMonths() {
        RecurringItem bill = new RecurringItem("Sample card bill", Money.of("500"), cat("OTHER_FIXED"));
        bill.setDayOfMonth(31);
        items.save(bill);
        posting.postDue(LocalDate.of(2032, 2, 29));
        assertThat(linked(bill, YearMonth.of(2032, 2))).singleElement()
                .satisfies(t -> assertThat(t.getDate()).isEqualTo(LocalDate.of(2032, 2, 29)));
    }

    @Test
    void deletedPostingIsNotPostedAgainButCanBePostedByHand() {
        RecurringItem gym = new RecurringItem("Sample gym", Money.of("1200"), cat("OTHER_FIXED"));
        items.save(gym);
        YearMonth m = YearMonth.of(2032, 4);
        posting.postDue(m.atDay(2));
        Transaction posted = linked(gym, m).get(0);

        txService.delete(posted.getId());
        posting.postDue(m.atDay(3));
        assertThat(linked(gym, m)).isEmpty();

        posting.postItem(gym.getId(), m);
        posting.postItem(gym.getId(), m);
        assertThat(linked(gym, m)).as("posting by hand twice still gives one entry").hasSize(1);
    }

    @Test
    void itemAlreadyInTheMonthUnderTheSameNameIsNotDuplicated() {
        RecurringItem car = new RecurringItem("Sample car", Money.of("9000"), cat("CAR_PAYMENT"));
        items.save(car);
        // e.g. the same fixed cost came in with the sheet import, without a link to the item
        transactions.save(new Transaction(LocalDate.of(2032, 5, 1), TxType.EXPENSE, Money.of("9000"),
                cat("CAR_PAYMENT"), "sample CAR"));
        posting.postDue(LocalDate.of(2032, 5, 10));
        YearMonth m = YearMonth.of(2032, 5);
        assertThat(linked(car, m)).isEmpty();
        assertThat(transactions.findSame(cat("CAR_PAYMENT"), "Sample car", m.atDay(1), m.atEndOfMonth())).hasSize(1);
        assertThat(posting.status(m).items()).filteredOn(s -> s.item().getId().equals(car.getId()))
                .singleElement().satisfies(s -> assertThat(s.posted()).isNotNull());
    }

    @Test
    void pausedItemIsNotPosted() {
        RecurringItem paused = new RecurringItem("Sample paused", Money.of("300"), cat("OTHER_FIXED"));
        paused.setActive(false);
        items.save(paused);
        posting.postDue(LocalDate.of(2032, 6, 28));
        assertThat(linked(paused, YearMonth.of(2032, 6))).isEmpty();
    }

    @Test
    void installmentPaymentsArePostedMonthlyUntilPaidOff() {
        Installment phone = new Installment("Sample phone", Money.of("3000"), Money.of("1000"), 3,
                LocalDate.of(2032, 7, 1));
        phone.setCategory(cat("SHOPPING"));
        plans.save(phone);

        posting.postDue(LocalDate.of(2032, 6, 15));
        assertThat(phone.getPaidCount()).as("nothing before the first month").isZero();

        for (int month = 7; month <= 10; month++) {
            posting.postDue(LocalDate.of(2032, month, 1));
            posting.postDue(LocalDate.of(2032, month, 2));
        }
        assertThat(phone.getPaidCount()).isEqualTo(3);
        assertThat(phone.isFinished()).isTrue();
        assertThat(phone.getRemainingDebt()).isEqualByComparingTo("0");
        assertThat(payments(phone, YearMonth.of(2032, 9))).singleElement()
                .satisfies(t -> assertThat(t.getInstallmentNo()).isEqualTo(3));
        assertThat(payments(phone, YearMonth.of(2032, 10))).as("paid off: no 4th payment").isEmpty();
    }

    @Test
    void paymentNumberFollowsTheMonthEvenAfterAMissedMonth() {
        // First payment entered in September; nothing posted in October; November is payment 3.
        Installment phone = new Installment("Sample iPhone", Money.of("40000"), Money.of("2000"), 20,
                LocalDate.of(2032, 9, 1));
        phone.setCategory(cat("SHOPPING"));
        phone.setPaidCount(1);
        phone.setLastPostedMonth(LocalDate.of(2032, 9, 1));
        plans.save(phone);

        posting.postDue(LocalDate.of(2032, 11, 1));
        assertThat(payments(phone, YearMonth.of(2032, 11))).singleElement()
                .satisfies(t -> assertThat(t.getInstallmentNo()).isEqualTo(3));
        assertThat(phone.getPaidCount()).isEqualTo(3);

        posting.postPlan(phone.getId(), YearMonth.of(2032, 10));   // add the missed month by hand
        assertThat(payments(phone, YearMonth.of(2032, 10))).singleElement()
                .satisfies(t -> assertThat(t.getInstallmentNo()).isEqualTo(2));
        assertThat(phone.getPaidCount()).isEqualTo(3);
        assertThat(phone.getNextPaymentMonth()).isEqualTo(LocalDate.of(2032, 12, 1));
        posting.postPlan(phone.getId(), YearMonth.of(2034, 9));    // after the last payment month: nothing
        assertThat(payments(phone, YearMonth.of(2034, 9))).isEmpty();
    }

    @Test
    void deletingTheLatestPaymentLowersThePaidCount() {
        Installment tv = new Installment("Sample TV", Money.of("12000"), Money.of("1000"), 12,
                LocalDate.of(2032, 1, 1));
        tv.setCategory(cat("SHOPPING"));
        tv.setPaidCount(4);
        plans.save(tv);
        YearMonth m = YearMonth.of(2032, 5);
        posting.postDue(m.atDay(1));
        assertThat(tv.getPaidCount()).isEqualTo(5);
        assertThat(tv.getRemainingDebt()).isEqualByComparingTo("7000");

        txService.delete(payments(tv, m).get(0).getId());
        assertThat(tv.getPaidCount()).isEqualTo(4);
    }

    @Test
    void monthlyChargeIncludesFlatInterest() {
        assertThat(Installment.monthlyCharge(Money.of("10000"), 10, Money.of("0"))).isEqualByComparingTo("1000.00");
        assertThat(Installment.monthlyCharge(Money.of("10000"), 10, new java.math.BigDecimal("0.69")))
                .isEqualByComparingTo("1069.00");
        assertThat(Installment.monthlyCharge(Money.of("1000"), 3, Money.of("0"))).isEqualByComparingTo("333.33");
    }
}
