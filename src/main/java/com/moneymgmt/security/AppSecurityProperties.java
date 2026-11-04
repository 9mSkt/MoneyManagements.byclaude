package com.moneymgmt.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Login settings. Username and BCrypt hash come only from environment variables (APP_USERNAME, APP_PASSWORD_HASH). */
@ConfigurationProperties(prefix = "app")
public record AppSecurityProperties(String username, String passwordHash, Limits security) {

    public record Limits(int maxFailedLogins, int lockoutMinutes) {
    }
}
