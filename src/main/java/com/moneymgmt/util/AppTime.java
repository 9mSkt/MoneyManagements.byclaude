package com.moneymgmt.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** The app works in Thai time, wherever the server runs. */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    private AppTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static YearMonth thisMonth() {
        return YearMonth.now(ZONE);
    }

    /** "2026-11" → that month; anything else → null. */
    public static YearMonth parseMonth(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The given day in that month, moved back to the last day for short months (31 → 30 Nov). */
    public static LocalDate dayIn(YearMonth month, int day) {
        return month.atDay(Math.max(1, Math.min(day, month.lengthOfMonth())));
    }
}
