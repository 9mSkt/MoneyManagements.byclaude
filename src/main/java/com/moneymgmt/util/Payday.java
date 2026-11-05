package com.moneymgmt.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/** The owner's salary day: the 28th, or the last weekday before it when the 28th is a Saturday or Sunday. */
public final class Payday {

    private Payday() {
    }

    /** The day the salary is paid in {@code month}; null when {@code payday} is 0 (rule off). */
    public static LocalDate payDate(YearMonth month, int payday) {
        if (payday < 1) {
            return null;
        }
        LocalDate d = month.atDay(Math.min(payday, month.lengthOfMonth()));
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
