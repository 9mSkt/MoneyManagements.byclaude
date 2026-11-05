package com.moneymgmt.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Money helpers. Every amount in the app is a BigDecimal with scale 2, rounded HALF_UP. */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    public static BigDecimal of(String value) {
        return scale(new BigDecimal(value));
    }

    public static BigDecimal of(long value) {
        return scale(BigDecimal.valueOf(value));
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    /** part / whole × 100, scale 2. Returns 0 when whole is zero. */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return zero();
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, SCALE, ROUNDING);
    }

    /**
     * Reads an amount typed by the user: "1,250", "฿1250.5", " 99 " all work.
     * Returns null when the text is empty or not a number.
     */
    public static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        String clean = text.replace(",", "").replace("฿", "").replace(" ", "").trim();
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return scale(new BigDecimal(clean));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "12,345.00" style, always 2 decimals. */
    public static String format(BigDecimal value) {
        if (value == null) {
            return "";
        }
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(scale(value));
    }
}
