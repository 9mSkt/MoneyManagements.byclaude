package com.moneymgmt.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns the connection string Neon shows ({@code postgresql://user:password@host/db?sslmode=require}) into a JDBC
 * URL, user name and password. A URL that already starts with {@code jdbc:} is kept as it is.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    public static DatabaseUrl parse(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL is empty");
        }
        String u = url.trim();
        if (u.startsWith("jdbc:")) {
            return new DatabaseUrl(u, blankToNull(username), blankToNull(password));
        }
        if (!u.startsWith("postgres://") && !u.startsWith("postgresql://")) {
            throw new IllegalArgumentException("DATABASE_URL must start with postgresql:// or jdbc:postgresql://");
        }
        URI uri = URI.create(u.replaceFirst("^postgres(ql)?://", "http://"));
        String user = blankToNull(username);
        String pass = blankToNull(password);
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            user = decode(parts[0]);
            pass = parts.length > 1 ? decode(parts[1]) : pass;
        }
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        // channel_binding is a libpq option the JDBC driver does not know; sslmode=require is kept.
        query = query.replaceAll("[?&]channel_binding=[^&]*", "").replaceFirst("^&", "?");
        return new DatabaseUrl("jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query, user, pass);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
