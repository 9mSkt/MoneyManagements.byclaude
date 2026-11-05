package com.moneymgmt.web;

import java.util.List;

/** One entry of the main menu. {@code key} is used for the i18n label (nav.KEY) and the icon id. */
public record NavItem(String key, String path, int phase, boolean mobileTab) {

    /** Phases finished so far; menu items from later phases show a "P3"-style badge. */
    public static final int BUILT_PHASE = 3;

    public static final List<NavItem> ALL = List.of(
            new NavItem("dashboard", "/", 1, true),
            new NavItem("transactions", "/transactions", 2, true),
            new NavItem("fixed", "/fixed-costs", 2, false),
            new NavItem("budget", "/budget", 3, true),
            new NavItem("investments", "/investments", 4, true),
            new NavItem("tax", "/tax", 5, false),
            new NavItem("goals", "/goals", 6, false),
            new NavItem("reports", "/reports", 6, false),
            new NavItem("settings", "/settings", 1, false));

    public boolean isComingSoon() {
        return phase > BUILT_PHASE;
    }

    public boolean isActive(String currentPath) {
        if ("/".equals(path)) {
            return "/".equals(currentPath) || "/dashboard".equals(currentPath);
        }
        return currentPath != null && (currentPath.equals(path) || currentPath.startsWith(path + "/"));
    }
}
