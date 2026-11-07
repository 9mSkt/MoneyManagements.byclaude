package com.moneymgmt.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.moneymgmt.domain.AppSetting;
import com.moneymgmt.repo.AppSettingRepository;
import com.moneymgmt.util.AppTime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Once a day, copies every table of the app into a separate Google Sheet (one tab per table) as an extra backup
 * the owner can open anywhere. The owner's original money sheet is never touched: the copy goes to the sheet whose
 * ID is in GOOGLE_COPY_SHEET_ID, shared with the service account in GOOGLE_SERVICE_ACCOUNT_JSON.
 *
 * <p>The free Render app sleeps when idle, so instead of a fixed time the job checks every hour (while the app is
 * awake) whether today's copy has been made yet.
 */
@Service
public class SheetCopyService {

    private static final Logger log = LoggerFactory.getLogger(SheetCopyService.class);
    static final String API = "https://sheets.googleapis.com/v4/spreadsheets/";
    static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    static final String INFO_TAB = "about";

    public static final String KEY_SUCCESS = "google.copy.lastSuccess";
    public static final String KEY_ERROR = "google.copy.lastError";
    public static final String KEY_ATTEMPT = "google.copy.lastAttempt";

    /** What the Settings page shows. */
    public record Status(boolean configured, String serviceAccount, Instant lastSuccess, Instant lastAttempt,
                         String lastError) {
    }

    private final DataExportService export;
    private final AppSettingRepository settings;
    private final RestClient http;
    private final String sheetId;
    private final String credentialsJson;
    private Supplier<String> tokenSupplier;

    public SheetCopyService(DataExportService export, AppSettingRepository settings, RestClient.Builder http,
                            @Value("${app.google.sheet-id:}") String sheetId,
                            @Value("${app.google.credentials-json:}") String credentialsJson) {
        this.export = export;
        this.settings = settings;
        this.http = http.build();
        this.sheetId = sheetId == null ? "" : sheetId.trim();
        this.credentialsJson = decode(credentialsJson);
        this.tokenSupplier = this::serviceAccountToken;
    }

    /** For tests: use a fixed access token instead of signing in with the service account. */
    void useToken(Supplier<String> supplier) {
        this.tokenSupplier = supplier;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(sheetId) && StringUtils.hasText(credentialsJson);
    }

    public Status status() {
        return new Status(isConfigured(), serviceAccountEmail(), instant(KEY_SUCCESS), instant(KEY_ATTEMPT),
                settings.findById(KEY_ERROR).map(AppSetting::getValue).orElse(null));
    }

    /** Hourly check: makes today's copy if it has not been made yet (Thai date). */
    @Scheduled(initialDelayString = "${app.google.first-check:PT2M}", fixedDelayString = "${app.google.check-every:PT1H}")
    public void copyIfDue() {
        if (!isConfigured() || !isDue(instant(KEY_SUCCESS), Instant.now())) {
            return;
        }
        copyNow();
    }

    /** Due when there is no copy yet today (in Thai time). */
    static boolean isDue(Instant lastSuccess, Instant now) {
        if (lastSuccess == null) {
            return true;
        }
        LocalDate last = ZonedDateTime.ofInstant(lastSuccess, AppTime.ZONE).toLocalDate();
        LocalDate today = ZonedDateTime.ofInstant(now, AppTime.ZONE).toLocalDate();
        return last.isBefore(today);
    }

    /** Copies everything now. Returns true when it worked; the result is also saved for the Settings page. */
    public synchronized boolean copyNow() {
        save(KEY_ATTEMPT, Instant.now().toString());
        if (!isConfigured()) {
            save(KEY_ERROR, "Not set up: GOOGLE_COPY_SHEET_ID and GOOGLE_SERVICE_ACCOUNT_JSON are needed.");
            return false;
        }
        try {
            List<DataExportService.Table> tables = export.allTables();
            String token = tokenSupplier.get();
            ensureTabs(token, tables);
            writeAll(token, tables);
            save(KEY_SUCCESS, Instant.now().toString());
            settings.deleteById(KEY_ERROR);
            log.info("Google Sheet copy done: {} tables", tables.size());
            return true;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            String msg = "Google answered " + e.getStatusCode().value() + ": "
                    + (body.length() > 300 ? body.substring(0, 300) : body);
            log.warn("Google Sheet copy failed: {}", msg);
            save(KEY_ERROR, msg);
        } catch (RuntimeException e) {
            log.warn("Google Sheet copy failed", e);
            save(KEY_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return false;
    }

    // ---------------------------------------------------------------- Sheets API calls

    @SuppressWarnings("unchecked")
    private void ensureTabs(String token, List<DataExportService.Table> tables) {
        Map<String, Object> meta = http.get().uri(API + sheetId + "?fields=sheets.properties.title")
                .header("Authorization", "Bearer " + token).retrieve().body(Map.class);
        Set<String> existing = new HashSet<>();
        if (meta != null && meta.get("sheets") instanceof List<?> sheets) {
            for (Object s : sheets) {
                Object props = ((Map<String, Object>) s).get("properties");
                if (props instanceof Map<?, ?> p && p.get("title") != null) {
                    existing.add(p.get("title").toString());
                }
            }
        }
        List<Map<String, Object>> requests = new ArrayList<>();
        List<String> wanted = new ArrayList<>();
        wanted.add(INFO_TAB);
        tables.forEach(t -> wanted.add(t.name()));
        for (String title : wanted) {
            if (!existing.contains(title)) {
                requests.add(Map.of("addSheet", Map.of("properties", Map.of("title", title))));
            }
        }
        if (!requests.isEmpty()) {
            http.post().uri(API + sheetId + ":batchUpdate").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("requests", requests)).retrieve().toBodilessEntity();
        }
    }

    private void writeAll(String token, List<DataExportService.Table> tables) {
        List<String> ranges = new ArrayList<>();
        ranges.add(quote(INFO_TAB));
        tables.forEach(t -> ranges.add(quote(t.name())));
        http.post().uri(API + sheetId + "/values:batchClear").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("ranges", ranges)).retrieve().toBodilessEntity();

        List<Map<String, Object>> data = new ArrayList<>();
        data.add(Map.of("range", quote(INFO_TAB) + "!A1", "values", infoRows(tables)));
        for (DataExportService.Table t : tables) {
            data.add(Map.of("range", quote(t.name()) + "!A1", "values", values(t)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valueInputOption", "RAW");   // RAW: text is never run as a formula
        body.put("data", data);
        http.post().uri(API + sheetId + "/values:batchUpdate").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }

    private static List<List<Object>> infoRows(List<DataExportService.Table> tables) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Money Management – daily copy", ""));
        rows.add(List.of("copied_at (Thai time)", ZonedDateTime.now(AppTime.ZONE).toLocalDateTime().withNano(0).toString()));
        rows.add(List.of("", ""));
        rows.add(List.of("table", "rows"));
        for (DataExportService.Table t : tables) {
            rows.add(List.of(t.name(), t.rows().size()));
        }
        return rows;
    }

    /** Header row + data rows, as values the Sheets API accepts (numbers stay numbers, dates become text). */
    static List<List<Object>> values(DataExportService.Table t) {
        List<List<Object>> out = new ArrayList<>();
        out.add(new ArrayList<>(t.columns()));
        for (List<Object> row : t.rows()) {
            List<Object> r = new ArrayList<>(row.size());
            for (Object v : row) {
                r.add(cell(v));
            }
            out.add(r);
        }
        return out;
    }

    static Object cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().withNano(0).toString();
        }
        return v.toString();
    }

    private static String quote(String tab) {
        return "'" + tab.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- credentials

    private String serviceAccountToken() {
        try {
            GoogleCredentials creds = ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(List.of(SCOPE));
            creds.refreshIfExpired();
            return creds.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("Google sign-in failed. Check GOOGLE_SERVICE_ACCOUNT_JSON. " + e.getMessage(), e);
        }
    }

    /** The service account's e-mail (to share the sheet with), read from the JSON; null when not set. */
    private String serviceAccountEmail() {
        if (!StringUtils.hasText(credentialsJson)) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("\"client_email\"\\s*:\\s*\"([^\"]+)\"").matcher(credentialsJson);
        return m.find() ? m.group(1) : null;
    }

    /** The JSON may be pasted as is, or base64-encoded (easier in some dashboards). */
    static String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value.trim();
        if (v.startsWith("{")) {
            return v;
        }
        try {
            return new String(Base64.getDecoder().decode(v.replaceAll("\\s", "")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v;
        }
    }

    private Instant instant(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(s -> {
            try {
                return Instant.parse(s);
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
    }

    private void save(String key, String value) {
        AppSetting s = settings.findById(key).orElse(null);
        if (s == null) {
            s = new AppSetting(key, value);
        } else {
            s.set(value);
        }
        settings.save(s);
    }
}
